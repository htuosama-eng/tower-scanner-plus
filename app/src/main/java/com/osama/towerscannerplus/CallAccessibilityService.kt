package com.osama.towerscannerplus

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * خدمة وصول تضغط أزرار تطبيق الهاتف نيابة عن الأتمتة، فتتم المكالمات
 * (اتصال/إنهاء/رد) من داخل تطبيق الهاتف نفسه، دون إذن اتصال
 * ودون تعيين هذا التطبيق كتطبيق الهاتف الافتراضي.
 *
 * تعمل فقط عندما تطلب الأتمتة إجراءً (pendingAction في CallPrefs)
 * ولفترة قصيرة محددة، ثم تتوقف عن الضغط تلقائياً.
 */
class CallAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val prefs = CallPrefs(this)
        val action = prefs.pendingAction
        if (action.isEmpty()) return
        if (System.currentTimeMillis() > prefs.pendingUntilMs) {
            prefs.clearPending()
            return
        }
        val root = rootInActiveWindow ?: return
        val words = when (action) {
            CallPrefs.PENDING_CALL -> CALL_WORDS
            CallPrefs.PENDING_END -> END_WORDS
            CallPrefs.PENDING_ANSWER -> ANSWER_WORDS
            else -> return
        }
        // أولاً: مطابقة وصف الزر (أدق)، ثم مطابقة نصه.
        if (clickMatchingNode(root, words, descOnly = true) ||
            clickMatchingNode(root, words, descOnly = false)
        ) {
            prefs.clearPending()
        }
    }

    override fun onInterrupt() {}

    private fun clickMatchingNode(
        node: AccessibilityNodeInfo,
        words: List<String>,
        descOnly: Boolean
    ): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val haystack = if (descOnly) desc else "$text $desc"
        val className = node.className?.toString().orEmpty()
        val looksLikeButton = node.isClickable ||
            className.endsWith("Button") ||
            className.endsWith("ImageButton")
        if (looksLikeButton && node.isEnabled && node.isVisibleToUser && haystack.isNotBlank()) {
            val lower = haystack.lowercase()
            if (words.any { w -> lower.contains(w) }) {
                if (performClick(node)) return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickMatchingNode(child, words, descOnly)) return true
        }
        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return try {
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        val CALL_WORDS = listOf("اتصال", "call", "dial")
        val END_WORDS = listOf("إنهاء", "end call", "end")
        val ANSWER_WORDS = listOf("رد", "answer", "accept", "قبول")
    }
}
