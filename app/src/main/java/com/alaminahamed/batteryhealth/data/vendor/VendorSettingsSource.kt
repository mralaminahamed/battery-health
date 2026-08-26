package com.alaminahamed.batteryhealth.data.vendor

import android.content.Context
import android.provider.Settings
import com.alaminahamed.batteryhealth.domain.Reading
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vendor battery facts published in the `Settings` provider, readable with no permission.
 *
 * This is the only source in the app that yields a vendor's own value with nothing asked
 * of the user at all -- no runtime permission, no privileged shell, no per-boot command.
 * Where a vendor publishes something here, it is strictly better than the same value
 * obtained through the privileged tier, because the user gets it for free.
 *
 * Reads go through the app's own `ContentResolver`. That distinction matters: `adb shell
 * settings list` runs as the `shell` user and sees keys an ordinary app cannot, so shell
 * visibility is not evidence the app can read something.
 */
@Singleton
class VendorSettingsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : VendorReadings {
    /**
     * Battery Protect's on/off state, or [Reading.Unsupported] where the vendor does not
     * publish it -- which is every non-Samsung device.
     *
     * `SecurityException` is caught rather than assumed impossible. Reading
     * `Settings.Global` needs no permission today, but this crosses into a provider the
     * app does not own, and a vendor is free to restrict a key of its own. A refusal means
     * the same thing to the caller as an absent key: the app has no value to show.
     */
    override fun batteryProtectEnabled(): Reading<Boolean> = try {
        VendorBatteryProtect.interpret(
            Settings.Global.getString(context.contentResolver, VendorBatteryProtect.KEY),
        )
    } catch (t: Throwable) {
        Reading.Unsupported
    }

    /**
     * The charge percentage Battery Protect stops at.
     *
     * Preferred over the privileged tier's `mProtectionThreshold`, which is the Maximum
     * slider's floor rather than the value selected -- see [VendorBatteryProtectThreshold].
     * This one matches what Samsung's own Battery protection screen tells the user.
     */
    override fun batteryProtectThresholdPct(): Reading<Int> = try {
        VendorBatteryProtectThreshold.interpretThreshold(
            Settings.Global.getString(context.contentResolver, VendorBatteryProtectThreshold.KEY),
        )
    } catch (t: Throwable) {
        Reading.Unsupported
    }
}
