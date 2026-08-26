package com.alaminahamed.batteryhealth.di

import com.alaminahamed.batteryhealth.data.apps.AppLabelResolver
import com.alaminahamed.batteryhealth.data.apps.FullAppLabelResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Same fully-qualified class name as `play`'s own `di/AppsModule.kt`; only one of the two
 * is ever on the compile path for a given build variant, so nothing outside this file --
 * not even Hilt's own generated code -- has to know which one was chosen. Everything
 * that depends on [AppLabelResolver] (`AppCpuRowMapper`, `AppsViewModel`) asks Hilt for
 * the interface and receives whichever concrete type this flavour bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppsModule {
    @Binds
    abstract fun bindAppLabelResolver(impl: FullAppLabelResolver): AppLabelResolver

    companion object {
        /**
         * True: this flavour declares `QUERY_ALL_PACKAGES` in its own
         * `AndroidManifest.xml`, which is exactly what lets [FullAppLabelResolver]
         * differ from `PlayAppLabelResolver`. `SettingsViewModel`'s Permissions section
         * reads this to decide whether to render a row for it -- the same flag this app
         * used to reuse from the now-removed privileged-tier module, since the
         * `full`/`play` split it needs to track is this one, not that one.
         */
        @Provides
        @Named("queryAllPackagesDeclared")
        fun provideQueryAllPackagesDeclared(): Boolean = true
    }
}
