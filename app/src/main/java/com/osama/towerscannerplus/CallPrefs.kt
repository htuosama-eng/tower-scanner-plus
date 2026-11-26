package com.osama.towerscannerplus

import android.content.Context

/** إعدادات قسم الاتصال — محفوظة محلياً في التطبيق. */
class CallPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("call_settings", Context.MODE_PRIVATE)

    var number: String
        get() = prefs.getString(KEY_NUMBER, "") ?: ""
        set(v) { prefs.edit().putString(KEY_NUMBER, v.trim()).apply() }

    /** مدة المكالمة الصادرة بعد الرد، بالثواني. */
    var outDurationSec: Int
        get() = prefs.getInt(KEY_OUT_SEC, 15).coerceIn(1, 3600)
        set(v) { prefs.edit().putInt(KEY_OUT_SEC, v.coerceIn(1, 3600)).apply() }

    /** مدة الرد على المكالمة الواردة، بالثواني. */
    var inDurationSec: Int
        get() = prefs.getInt(KEY_IN_SEC, 15).coerceIn(1, 3600)
        set(v) { prefs.edit().putInt(KEY_IN_SEC, v.coerceIn(1, 3600)).apply() }

    /** مفتاح البرج المثبّت، مثل "LTE:123456:789". */
    var pinnedTowerKey: String?
        get() = prefs.getString(KEY_PINNED_KEY, null)
        set(v) { prefs.edit().putString(KEY_PINNED_KEY, v).apply() }

    var pinnedTowerLabel: String?
        get() = prefs.getString(KEY_PINNED_LABEL, null)
        set(v) { prefs.edit().putString(KEY_PINNED_LABEL, v).apply() }

    var automationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** إعادة الاتصال تلقائياً بعد الفصل طالما أنت على البرج المثبّت. */
    var autoRepeat: Boolean
        get() = prefs.getBoolean(KEY_REPEAT, true)
        set(v) { prefs.edit().putBoolean(KEY_REPEAT, v).apply() }

    companion object {
        const val KEY_NUMBER = "number"
        const val KEY_OUT_SEC = "out_duration_sec"
        const val KEY_IN_SEC = "in_duration_sec"
        const val KEY_PINNED_KEY = "pinned_tower_key"
        const val KEY_PINNED_LABEL = "pinned_tower_label"
        const val KEY_ENABLED = "automation_enabled"
        const val KEY_REPEAT = "auto_repeat"

        const val ACTION_REFRESH = "com.osama.towerscannerplus.CALL_STATUS_REFRESH"
        const val EXTRA_STATUS = "status"

        /** توحيد الرقم للمقارنة: أرقام فقط، مع إزالة + والمسافات. */
        fun normalizeNumber(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val digits = raw.filter { it.isDigit() }
            // قارن بآخر 9 أرقام حتى لا يفرق وجود رمز الدولة من عدمه.
            return if (digits.length > 9) digits.takeLast(9) else digits
        }
    }
}
