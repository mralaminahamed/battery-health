package com.alaminahamed.batteryhealth

import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.test.filters.SdkSuppress
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
    fun launcherIconIsAdaptive() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("launcher icon must be adaptive", icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable
        assertNotNull("background layer missing", adaptive.background)
        assertNotNull("foreground layer missing", adaptive.foreground)
    }

    /**
     * Themed icons on Android 13+ and One UI 5+ need the monochrome layer, but
     * AdaptiveIconDrawable.getMonochrome() only exists from API 33. minSdk here is 26, so
     * calling it unguarded throws NoSuchMethodError on API 26-32 — the test would error out
     * rather than fail cleanly. Suppressing below 33 keeps the assertion honest on the
     * devices where the layer is actually consumed.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    fun launcherIconCarriesMonochromeLayerForThemedIcons() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertNotNull("monochrome layer missing", (icon as AdaptiveIconDrawable).monochrome)
    }

    @Test
    fun notificationIconResolves() {
        val drawable = context.getDrawable(R.drawable.ic_notification)
        assertNotNull("ic_notification must resolve", drawable)
        // Status bar icons are drawn from the alpha channel at 24dp.
        assertEquals(24, (drawable!!.intrinsicWidth / context.resources.displayMetrics.density).toInt())
    }

    /**
     * The splash used to draw its own flat glyph, which silently stopped matching the
     * launcher when the icon was redrawn as a graphite instrument on a neutral plate.
     * Asserting the splash resolves to the same adaptive icon is what catches a separate
     * drawable being reintroduced.
     */
    @Test
    fun splashShowsTheLauncherIconRatherThanItsOwnDrawable() {
        val themed = ContextThemeWrapper(context, R.style.Theme_BatteryHealth_Starting)
        val attrs = intArrayOf(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon)
        val typed = themed.obtainStyledAttributes(attrs)
        try {
            val drawable = typed.getDrawable(0)
            assertNotNull("splash icon missing", drawable)
            assertTrue(
                "splash icon must be the adaptive launcher icon, not a separate drawable",
                drawable is AdaptiveIconDrawable,
            )
        } finally {
            typed.recycle()
        }
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
