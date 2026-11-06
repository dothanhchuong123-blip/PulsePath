package com.soundsight.ui.theme

import android.os.Build
import androidx.compose.ui.graphics.Color

/**
 * Whether this device actually builds a Material You palette from the wallpaper.
 *
 * An API-level check alone lies: a HyperOS phone reports Android 12+ yet never populates
 * `android.theme.customization.system_palette`, so its "dynamic" palette is the same fixed
 * framework blue whatever the wallpaper is - and a wallpaper toggle that swaps identical
 * palettes must say so instead of pretending to work.
 *
 * Measured 2026-08-24 on the target device (Xiaomi 13, 2211133C "fuxi", Android 16 / API 36):
 * `theme_customization_overlay_packages` carries `color_source=lock_wallpaper` but NO
 * `system_palette` key, and `dynamicLightColorScheme(context).primary` equals the framework
 * default `#475D92` - i.e. [NO_EXTRACTION]. The default-primary comparison is the second,
 * behavioural check: a ROM that extracts differently would not return the baseline blue.
 */
enum class WallpaperPaletteSupport {
    /** Android 12+ with a real wallpaper-derived palette: the toggle does something visible. */
    AVAILABLE,

    /** Android 12+ but the palette is the fixed framework default: toggle is a no-op here. */
    NO_EXTRACTION,

    /** Pre-Android 12: no dynamic colour API at all. */
    UNSUPPORTED_API
}

/** The framework's default dynamic light primary, measured on the target device (see above). */
val FrameworkDefaultDynamicLightPrimary = Color(0xFF475D92)

fun wallpaperPaletteSupport(
    apiLevel: Int,
    systemPaletteRaw: String?,
    dynamicLightPrimary: Color?
): WallpaperPaletteSupport {
    if (apiLevel < Build.VERSION_CODES.S) return WallpaperPaletteSupport.UNSUPPORTED_API
    val hasSystemPalette = systemPaletteRaw?.contains("android.theme.customization.system_palette") == true
    if (hasSystemPalette) return WallpaperPaletteSupport.AVAILABLE
    // No system_palette key: the ROM is not feeding the framework a wallpaper palette. The only
    // way dynamic colour could still differ from the default is a ROM with its own extraction -
    // detectable by the palette NOT being the framework baseline.
    if (dynamicLightPrimary != null && dynamicLightPrimary != FrameworkDefaultDynamicLightPrimary) {
        return WallpaperPaletteSupport.AVAILABLE
    }
    return WallpaperPaletteSupport.NO_EXTRACTION
}
