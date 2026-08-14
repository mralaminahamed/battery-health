package com.mralaminahamed.batteryhealth.data.apps

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * `play` does **not** declare `QUERY_ALL_PACKAGES` (Play does not approve it for a
 * battery tool's declared category -- see this feature's task report), so
 * [PackageManager][android.content.pm.PackageManager] here can only see this app's own
 * package, whatever `<queries>` names (Shizuku), and packages the platform already
 * considers visible for other reasons. For every uid whose package sits outside that
 * narrow set -- the overwhelming majority `batterystats`' own uid dictionary names --
 * `getApplicationInfo` throws `NameNotFoundException`, and [resolveViaPackageManager]
 * returns [AppLabel.PackageNameOnly] rather than inventing a label: the raw package
 * identifier is real (`batterystats` reported it), but this build genuinely cannot
 * confirm what a human would call it, and says so rather than guessing.
 *
 * This class itself does not contain the reduced-visibility behaviour or any
 * flavour-specific branch; the *absence* of a manifest declaration is the entire
 * difference from [FullAppLabelResolver][com.mralaminahamed.batteryhealth.data.apps] --
 * see [resolveViaPackageManager]'s own doc for why the lookup logic is shared rather than
 * duplicated here.
 */
class PlayAppLabelResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLabelResolver {
    override fun resolve(packageNames: List<String>): AppLabel =
        resolveViaPackageManager(context.packageManager, packageNames)
}
