package com.alaminahamed.batteryhealth.di

import com.alaminahamed.batteryhealth.data.apps.AppLabelResolver
import com.alaminahamed.batteryhealth.data.apps.FullAppLabelResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Same fully-qualified class name as `play`'s own `di/AppsModule.kt`; only one of the two
 * is ever on the compile path for a given build variant, so nothing outside this file --
 * not even Hilt's own generated code -- has to know which one was chosen. Everything
 * that depends on [AppLabelResolver] (`AppRowMapper`, `AppsViewModel`) asks Hilt for the
 * interface and receives whichever concrete type this flavour bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppsModule {
    @Binds
    abstract fun bindAppLabelResolver(impl: FullAppLabelResolver): AppLabelResolver
}
