package com.arslan.textgrab

import android.app.Application
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.Bundle
import com.google.android.material.color.DynamicColors
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start in Vietnamese unless the user has already chosen an app language in Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            if (localeManager.applicationLocales.isEmpty) {
                localeManager.applicationLocales = LocaleList.forLanguageTags("vi")
            }
        }
        DynamicColors.applyToActivitiesIfAvailable(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { LiveSession.visibleActivities++ }
            override fun onActivityStopped(activity: Activity) { LiveSession.visibleActivities = (LiveSession.visibleActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        fun localizedContext(base: Context): Context {
            val appLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)
            } else {
                null
            }
            val locale = appLocale ?: Locale.forLanguageTag("vi")
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocale(locale)
            configuration.setLayoutDirection(locale)
            return base.createConfigurationContext(configuration)
        }
    }
}
