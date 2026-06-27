package com.example.a4pdaupdatechecker

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsControllerCompat

object ThemeHelper {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME = "selected_theme"
    private const val KEY_AMOLED = "amoled_enabled"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    fun applyTheme(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val theme = prefs.getInt(KEY_THEME, THEME_SYSTEM)

        when (theme) {
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    /**
     * Применяет AMOLED тему к конкретной Activity, если режим включен.
     * Должно вызываться ДО super.onCreate().
     */
    fun applyAmoledToActivity(activity: Activity) {
        if (isAmoledEnabled(activity)) {
            activity.setTheme(R.style.AppTheme_Amoled)
        }
    }

    /**
     * Настраивает цвет иконок статусбара в зависимости от текущей темы.
     */
    fun updateStatusBarIcons(activity: Activity) {
        val isAmoled = isAmoledEnabled(activity)
        val currentNightMode = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNight = currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        // В светлой теме иконки должны быть темными. 
        // В темной и AMOLED - светлыми.
        val useLightIcons = !isNight && !isAmoled
        
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = useLightIcons
        controller.isAppearanceLightNavigationBars = useLightIcons
    }

    fun getSelectedTheme(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_THEME, THEME_SYSTEM)
    }

    fun setSelectedTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, theme).apply()
        applyTheme(context)
    }

    fun isAmoledEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AMOLED, false)
    }

    fun setAmoledEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AMOLED, enabled).apply()
    }
}