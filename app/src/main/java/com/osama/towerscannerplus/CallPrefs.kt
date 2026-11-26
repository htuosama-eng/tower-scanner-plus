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

    /** إجراء معلّق لخدمة الوصول: CALL أو END أو ANSWER، أو "" بلا إجراء. */
    var pendingAction: String
        get() = prefs.getString(KEY_PENDING_ACTION, "") ?: ""
        set(v) { prefs.edit().putString(KEY_PENDING_ACTION, v).apply() }

    /** آخر لحظة صالحة للإجراء المعلق (مللي ثانية). */
    var pendingUntilMs: Long
        get() = prefs.getLong(KEY_PENDING_UNTIL, 0L)
        set(v) { prefs.edit().putLong(KEY_PENDING_UNTIL, v).apply() }

    fun setPending(action: String, validMs: Long) {
        pendingAction = action
        pendingUntilMs = System.currentTimeMillis() + validMs
    }

    fun clearPending() {
        pendingAction = ""
        pendingUntilMs = 0L
    }

    /** نتيجة آخر محاولة ضغط زر (مثل "CALL:ok" أو "CALL:failed"). */
    var lastClickResult: String
        get() = prefs.getString(KEY_LAST_CLICK, "") ?: ""
        set(v) { prefs.edit().putString(KEY_LAST_CLICK, v).apply() }

    /** حتى متى تتوقف محاولات الاتصال التلقائي بعد فشل متكرر (مللي ثانية). */
    var dialBlockedUntilMs: Long
        get() = prefs.getLong(KEY_DIAL_BLOCKED_UNTIL, 0L)
        set(v) { prefs.edit().putLong(KEY_DIAL_BLOCKED_UNTIL, v).apply() }

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
        const val KEY_PENDING_ACTION = "pending_action"
        const val KEY_PENDING_UNTIL = "pending_until_ms"
        const val KEY_LAST_CLICK = "last_click_result"
        const val KEY_DIAL_BLOCKED_UNTIL = "dial_blocked_until_ms"

        /** إجراءات خدمة الوصول المعلّقة. */
        const val PENDING_CALL = "CALL"
        const val PENDING_END = "END"
        const val PENDING_ANSWER = "ANSWER"

        /** هل خدمة الوصول الخاصة بالتطبيق مفعّلة في إعدادات الهاتف؟ */
        fun isAccessibilityEnabled(context: Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.contains("CallAccessibilityService")
            } catch (_: Exception) {
                false
            }
        }

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
