package com.mralaminahamed.batteryhealth.data.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.mralaminahamed.batteryhealth.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place this app calls into Shizuku's static API. Everything else -- the
 * repository, the ViewModel, the Health screen -- depends on [PrivilegedBatterySource]
 * instead, precisely so nothing else needs to know Shizuku's API shape or its global,
 * process-wide listener registry.
 *
 * [state] is a live combination of four independently-tracked facts (package installed,
 * binder alive, permission granted, `UserService` bound), reduced through
 * [shizukuAvailability]'s pure decision table rather than any single boolean -- see that
 * function's own doc for why collapsing them would leave the UI unable to say what the
 * user should actually do next.
 *
 * Binds a single `UserService` once permission is granted and the binder is alive
 * ([bindUserServiceIfReady]), rather than spawning a fresh Shizuku-hosted process per
 * `dumpsys battery` call. A `UserService` process is not free to start (Shizuku itself has
 * to `fork`+`exec` it under the shell UID), and these fields change on the order of
 * firmware updates, not seconds -- paying that cost once per bind and reusing the bound
 * `Binder` for every later query is the right trade here, not a premature optimisation.
 */
@Singleton
class ShizukuGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : PrivilegedBatterySource {

    // No natural owner to cancel this for: like BatteryManagerSource's own DataStore
    // collector, this is a @Singleton meant to live exactly as long as the process does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val packageInstalled = MutableStateFlow(isPackageInstalled())
    private val binderAlive = MutableStateFlow(pingBinderSafely())
    private val permissionGranted = MutableStateFlow(readPermissionGranted())
    private val serviceBound = MutableStateFlow(false)

    @Volatile private var boundService: IUserService? = null
    private var connection: ServiceConnection? = null

    override val state: StateFlow<ShizukuAvailability> = combine(
        packageInstalled,
        binderAlive,
        permissionGranted,
        serviceBound,
    ) { installed, alive, granted, bound ->
        shizukuAvailability(installed, alive, granted, bound)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = shizukuAvailability(
            packageInstalled = packageInstalled.value,
            binderAlive = binderAlive.value,
            permissionGranted = permissionGranted.value,
            serviceBound = serviceBound.value,
        ),
    )

    /**
     * Sticky, not merely `addBinderReceivedListener`: this listener must also fire
     * immediately if Shizuku's binder was already alive *before* this singleton was
     * constructed -- a warm relaunch of this app's process while Shizuku keeps running
     * across it -- not only on a future transition from dead to alive.
     */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Reachable at all only because Shizuku is genuinely running, which is also
        // proof of installation even if the packageManager check below somehow raced it.
        packageInstalled.value = true
        binderAlive.value = true
        permissionGranted.value = readPermissionGranted()
        bindUserServiceIfReady()
    }

    /**
     * The line this whole class exists for. Shizuku disappearing mid-session -- killed
     * from its own app, an ADB session dropping, the device rebooting -- flips straight
     * to [ShizukuAvailability.NotRunning] through [state], live, with no exception
     * anywhere downstream: every privileged `Reading` this feeds degrades to
     * `NeedsShizuku` on `BatteryRepository`'s very next emission, not on a crash and
     * restart. Verified for real by killing Shizuku while this app stayed open -- see
     * the task report.
     */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive.value = false
        serviceBound.value = false
        boundService = null
        connection = null
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            permissionGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
            bindUserServiceIfReady()
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        // Covers the case the sticky listener above does not: permission was already
        // granted on a previous run (Shizuku remembers grants across this app's own
        // restarts) and the binder is already alive by the time this constructor runs,
        // with no fresh "binder received" event ever firing to trigger a bind.
        bindUserServiceIfReady()
    }

    override suspend fun dumpBattery(): String? {
        val service = boundService ?: return null
        return withContext(Dispatchers.IO) {
            // A blank result and a thrown RemoteException are the same "no dump to
            // parse" from this call's point of view -- both become null, so
            // BatteryRepository cannot tell (and does not need to tell) a shell-side
            // failure from a shell-side empty string.
            runCatching { service.dumpBattery() }.getOrNull()?.ifBlank { null }
        }
    }

    override fun requestPermission() {
        if (!binderAlive.value || permissionGranted.value) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    override fun refresh() {
        packageInstalled.value = isPackageInstalled()
        binderAlive.value = pingBinderSafely()
        if (binderAlive.value) {
            permissionGranted.value = readPermissionGranted()
            bindUserServiceIfReady()
        }
    }

    private fun bindUserServiceIfReady() {
        if (!binderAlive.value || !permissionGranted.value || serviceBound.value) return
        val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedBatteryService::class.java))
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = binder?.let(IUserService.Stub::asInterface)
                serviceBound.value = boundService != null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
                serviceBound.value = false
            }
        }
        connection = conn
        runCatching { Shizuku.bindUserService(args, conn) }
    }

    private fun pingBinderSafely(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun readPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun isPackageInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val PERMISSION_REQUEST_CODE = 815
    }
}
