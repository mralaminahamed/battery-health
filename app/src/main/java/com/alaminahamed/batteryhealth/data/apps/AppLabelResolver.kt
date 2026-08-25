package com.alaminahamed.batteryhealth.data.apps

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Turns a uid's package name(s), as recovered from `dumpsys batterystats --checkin`'s own
 * uid dictionary, into what this build is actually allowed to show for it.
 *
 * `full` and `play` answer this differently -- see `FullAppLabelResolver` (`src/full/`)
 * and `PlayAppLabelResolver` (`src/play/`) -- because only `full` declares
 * `QUERY_ALL_PACKAGES` in its own `AndroidManifest.xml`; Play does not approve that
 * permission for a battery tool's declared category, so `play` omits it and
 * `PackageManager` itself, not any branch in this app's own code, is what then refuses to
 * resolve most packages. **Nothing outside this interface and its two flavour
 * implementations is allowed to know which flavour it is running in** -- a caller
 * receives an [AppLabel] and renders whichever case it turns out to be, the same
 * discipline [Reading][com.alaminahamed.batteryhealth.domain.Reading] already enforces
 * for every other metric in this app. If a call site elsewhere ever needs to branch on
 * `BuildConfig.FLAVOR`, that is a sign this seam is in the wrong place, not a reason to
 * add such a branch.
 */
interface AppLabelResolver {
    /**
     * [packageNames] is every package `batterystats` attributes to one uid -- usually
     * one, sometimes none (a uid the dump measured power for but never named a package
     * for), occasionally dozens (a shared `android:sharedUserId`; uid `1000` alone owns
     * 82 on the real fixture this was built against). Resolves against the *first*
     * package this build can actually see, never all of them individually: this app
     * attributes one power figure to the uid as a whole, not to any one package inside
     * it, so resolving (and rendering an icon for) one arbitrary member of a
     * dozens-strong shared uid would misrepresent the row as being about that one
     * package specifically.
     */
    fun resolve(packageNames: List<String>): AppLabel
}

/**
 * What came back for one uid's package name(s). Three cases, not two -- collapsing
 * [PackageNameOnly] into [Resolved] with the package name standing in for a real label
 * would tell the user a raw identifier like `com.sec.android.app.camera` IS the app's
 * name, when the honest fact is that this build could not confirm the real one;
 * collapsing [Unknown] into [PackageNameOnly] would print an empty or placeholder string
 * as if it were a genuine identifier `batterystats` gave us, when in fact it gave us
 * nothing at all for this uid. The distinction between "this is the app's confirmed name"
 * and "this is all I can see" is exactly the provenance discipline this whole app is
 * built on, applied here instead of to a numeric `Reading`.
 */
sealed interface AppLabel {
    /** A real app label (and, where available, its launcher icon) resolved through
     * `PackageManager`. The honest, confirmed identity -- never shown for a uid this
     * build could not actually verify. */
    data class Resolved(val label: String, val icon: Drawable?) : AppLabel

    /** This build could not resolve a label for [packageName] -- most commonly `play`'s
     * reduced package visibility, occasionally a package that named a uid in the dump
     * but is no longer installed on either flavour. [packageName] is real (`batterystats`
     * reported it); the *label* is what is unavailable, and this is rendered as the raw
     * identifier, never disguised as a resolved name. */
    data class PackageNameOnly(val packageName: String) : AppLabel

    /** `batterystats` recorded power for this uid but its own uid dictionary named no
     * package for it at all -- there is no identifier of any kind to fall back to, not
     * even an unresolved one. Independent of which flavour this is: neither build can
     * resolve a package name that was never given in the first place. */
    data object Unknown : AppLabel
}

/**
 * The one PackageManager call both flavours' resolvers make, shared here so
 * `FullAppLabelResolver` and `PlayAppLabelResolver` are not two independently-maintained
 * copies of the same lookup. This is deliberately *not* where the flavours differ: the
 * code path is identical in both -- the same `getApplicationInfo` call, the same
 * `runCatching` -- and what actually changes between builds is entirely the OS-enforced
 * package-visibility filtering `PackageManager` applies underneath it, driven by whether
 * `QUERY_ALL_PACKAGES` is declared in the calling flavour's own manifest. Keeping the
 * difference in the manifest, not in a second copy of this logic, is what keeps this
 * function honestly shared rather than coincidentally identical.
 */
internal fun resolveViaPackageManager(packageManager: PackageManager, packageNames: List<String>): AppLabel {
    val packageName = packageNames.firstOrNull() ?: return AppLabel.Unknown
    val info = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        ?: return AppLabel.PackageNameOnly(packageName)
    val label = packageManager.getApplicationLabel(info).toString()
    val icon = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
    return AppLabel.Resolved(label, icon)
}
