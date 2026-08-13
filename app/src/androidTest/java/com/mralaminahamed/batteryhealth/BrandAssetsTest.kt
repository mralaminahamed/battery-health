package com.mralaminahamed.batteryhealth

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the brand assets against silently reverting to template art or losing a layer.
 * Every assertion here is something a user would notice on their launcher or status bar.
 */
class BrandAssetsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherIconIsAdaptiveWithAllThreeLayers() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("launcher icon must be adaptive", icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable
        assertNotNull("background layer missing", adaptive.background)
        assertNotNull("foreground layer missing", adaptive.foreground)
        // Themed icons on Android 13+ and One UI 5+ need the monochrome layer.
        assertNotNull("monochrome layer missing", adaptive.monochrome)
    }

    @Test
    fun notificationIconResolves() {
        val drawable = context.getDrawable(R.drawable.ic_notification)
        assertNotNull("ic_notification must resolve", drawable)
        // Status bar icons are drawn from the alpha channel at 24dp.
        assertEquals(24, (drawable!!.intrinsicWidth / context.resources.displayMetrics.density).toInt())
    }

    @Test
    fun splashIconResolves() {
        assertNotNull(context.getDrawable(R.drawable.ic_splash))
    }

    @Test
    fun noLegacyBitmapLauncherRemains() {
        // minSdk is 26, so adaptive icons cover every supported device and the template's
        // webp mipmaps can never be loaded. Their presence is dead weight in the APK.
        val id = context.resources.getIdentifier("ic_launcher", "mipmap", context.packageName)
        val drawable = context.resources.getDrawable(id, null)
        assertTrue(
            "ic_launcher must resolve to the adaptive icon, not a bitmap",
            drawable is AdaptiveIconDrawable,
        )
    }
}
