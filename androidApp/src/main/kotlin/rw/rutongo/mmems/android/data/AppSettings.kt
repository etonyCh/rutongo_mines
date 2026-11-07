package rw.rutongo.mmems.android.data

import android.content.Context
import android.os.Build

class AppSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("mmems_settings", Context.MODE_PRIVATE)

    var apiBaseUrl: String
        get() = prefs.getString(KEY_API, DEFAULT_API)?.trimEnd('/') ?: DEFAULT_API
        set(value) {
            prefs.edit().putString(KEY_API, value.trim().trimEnd('/')).apply()
        }

    val deviceLabel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        const val DEFAULT_API = "http://10.0.2.2:8080"
        private const val KEY_API = "api_base_url"
    }
}
