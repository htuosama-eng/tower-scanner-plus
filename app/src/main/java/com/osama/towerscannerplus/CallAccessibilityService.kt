package com.osama.towerscannerplus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * خدمة وصول تضغط أزرار تطبيق الهاتف نيابة عن الأتمتة، فتتم المكالمات
 * (اتصال/إنهاء/رد) من داخل تطبيق الهاتف نفسه، دون إذن اتصال
 * ودون تعيين هذا التطبيق كتطبيق الهاتف الافتراضي.
 *
 * تعمل فقط عندما تطلب الأتمتة إجراءً (pendingAction في CallPrefs)
 * ولفترة قصيرة محددة، وتعيد المحاولة كل 400 مللي ثانية حتى يظهر الزر
 * ويُضغط، ثم تتوقف تلقائياً. وتسجّل نتيجة المحاولة ليظهر التشخيص في التطبيق.
 */
class CallAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var loopRunning = false

    private val retryRunnable = object : Runnable {
        override fun run() {
            loopRunning = false
            val prefs = CallPrefs(this@CallAccessibilityService)
            val action = prefs.pendingAction
            if (action.isEmpty()) return
            if (System.currentTimeMillis() > prefs.pendingUntilMs) {
                prefs.clearPending()
                prefs.lastClickResult = "$action:failed"
                return
            }
            if (tryClick(action)) {
                prefs.clearPending()
                prefs.lastClickResult = "$action:ok"
                return
            }
            // الزر لم يظهر بعد أو لم يُضغط: أعد المحاولة بعد قليل.
            loopRunning = true
            handler.postDelayed(this, 400L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        kickLoop()
        return START_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        kickLoop()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        super.onDestroy()
    }

    private fun kickLoop() {
        if (loopRunning) return
        loopRunning = true
        handler.post(retryRunnable)
    }

    private fun tryClick(action: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val words = when (action) {
            CallPrefs.PENDING_CALL -> CALL_WORDS
            CallPrefs.PENDING_END -> END_WORDS
            CallPrefs.PENDING_ANSWER -> ANSWER_WORDS
            else -> return false
        }
        val idWords = idWordsFor(action)
        // أولاً: مطابقة معرّف الزر أو وصفه (الأدق)، ثم مطابقة نصه.
        return clickMatchingNode(root, words, idWords, byIdOrDesc = true) ||
            clickMatchingNode(root, words, idWords, byIdOrDesc = false)
    }

    private fun clickMatchingNode(
        node: AccessibilityNodeInfo,
        words: List<String>,
        idWords: List<String>,
        byIdOrDesc: Boolean
    ): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val viewId = node.viewIdResourceName?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()
        val looksLikeButton = node.isClickable ||
            className.endsWith("Button") ||
            className.endsWith("ImageButton") ||
            className.contains("FloatingActionButton")
        if (looksLikeButton && node.isEnabled && node.isVisibleToUser) {
            val matched = if (byIdOrDesc) {
                val idLower = viewId.lowercase()
                val descLower = desc.lowercase()
                idWords.any { w -> idLower.contains(w) } ||
                    words.any { w -> descLower.contains(w) }
            } else {
                val textLower = text.lowercase()
                words.any { w -> textLower.contains(w) }
            }
            if (matched && performClick(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickMatchingNode(child, words, idWords, byIdOrDesc)) return true
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

        fun idWordsFor(action: String): List<String> = when (action) {
            CallPrefs.PENDING_CALL -> listOf(
                "dial", "call_button", "callbutton",
                "floating_action_button", "dialpad"
            )
            CallPrefs.PENDING_END -> listOf(
                "end_button", "endbutton", "end_call", "incall_end"
            )
            CallPrefs.PENDING_ANSWER -> listOf("answer", "accept")
            else -> emptyList()
        }
    }
}
