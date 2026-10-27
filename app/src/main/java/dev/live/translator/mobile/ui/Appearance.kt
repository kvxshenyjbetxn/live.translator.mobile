package dev.live.translator.mobile.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import dev.live.translator.mobile.R
import dev.live.translator.mobile.model.AccentPreset
import dev.live.translator.mobile.model.AppSettings
import dev.live.translator.mobile.model.ThemeMode
import dev.live.translator.mobile.settings.SettingsRepository

data class Appearance(val theme: ThemeMode = ThemeMode.DARK, val accent: AccentPreset = AccentPreset.DEFAULT) {
    companion object {
        fun of(settings: AppSettings) = Appearance(settings.theme, settings.accent)

        /** Reads the saved appearance and applies it to [activity]; call before super.onCreate. */
        fun apply(activity: Activity): Appearance {
            val appearance = of(SettingsRepository.shared(activity.filesDir).peek())
            activity.setTheme(if (appearance.theme == ThemeMode.AMOLED) R.style.AppTheme_Amoled else R.style.AppTheme)
            activity.theme.applyStyle(accentStyle(appearance.accent), true)
            return appearance
        }

        private fun accentStyle(accent: AccentPreset): Int = when (accent) {
            AccentPreset.LIME -> R.style.Accent_Lime
            AccentPreset.MINT -> R.style.Accent_Mint
            AccentPreset.SKY -> R.style.Accent_Sky
            AccentPreset.LAVENDER -> R.style.Accent_Lavender
            AccentPreset.LILAC -> R.style.Accent_Lilac
            AccentPreset.PINK -> R.style.Accent_Pink
            AccentPreset.CORAL -> R.style.Accent_Coral
            AccentPreset.APRICOT -> R.style.Accent_Apricot
            AccentPreset.YELLOW -> R.style.Accent_Yellow
        }
    }
}

fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return value.data
}
