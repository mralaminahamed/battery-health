package com.mralaminahamed.batteryhealth.data.apps

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * `full`'s own `AndroidManifest.xml` (this same source set) declares
 * `QUERY_ALL_PACKAGES`, so [PackageManager][android.content.pm.PackageManager] here can
 * resolve any installed package's label and icon regardless of whether this app has ever
 * otherwise interacted with it or declared it under `<queries>` -- exactly what battery
 * attribution needs, since `batterystats`' own uid dictionary can name any package on the
 * device, most of which this app has no other relationship with at all.
 *
 * This class itself does not contain the permission check or any flavour-specific
 * branch; the manifest declaration is the entire difference from
 * [PlayAppLabelResolver][com.mralaminahamed.batteryhealth.data.apps] -- see
 * [resolveViaPackageManager]'s own doc for why the lookup logic is shared rather than
 * duplicated here.
 */
class FullAppLabelResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLabelResolver {
    override fun resolve(packageNames: List<String>): AppLabel =
        resolveViaPackageManager(context.packageManager, packageNames)
}
