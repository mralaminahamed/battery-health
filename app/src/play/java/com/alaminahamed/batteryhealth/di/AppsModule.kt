package com.alaminahamed.batteryhealth.di

import com.alaminahamed.batteryhealth.data.apps.AppLabelResolver
import com.alaminahamed.batteryhealth.data.apps.PlayAppLabelResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Same fully-qualified class name as `full`'s own `di/AppsModule.kt`; only one of the two
 * is ever on the compile path for a given build variant, so nothing outside this file --
 * not even Hilt's own generated code -- has to know which one was chosen. Everything
 * that depends on [AppLabelResolver] (`AppsViewModel`, and through it
 * `EstimatedDrainReading`) asks Hilt for the interface and receives whichever concrete
 * type this flavour bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppsModule {
    @Binds
    abstract fun bindAppLabelResolver(impl: PlayAppLabelResolver): AppLabelResolver

    companion object {
        /**
         * False: Play does not approve `QUERY_ALL_PACKAGES` for a battery tool's
         * declared category, so this flavour's manifest omits it entirely -- see
         * `PlayAppLabelResolver`'s own doc. The Permissions section reads this to skip
         * a row for a permission this build never declares.
         */
        @Provides
        @Named("queryAllPackagesDeclared")
        fun provideQueryAllPackagesDeclared(): Boolean = false
    }
}
