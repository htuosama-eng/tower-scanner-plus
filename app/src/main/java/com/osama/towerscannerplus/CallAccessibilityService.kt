package com.osama.towerscannerplus

import android.accessibilityservice.AccessibilityService
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /** تُستدعى من خدمة الأتمتة لبدء محاولة الضغط فوراً. */
    fun kick() {
        handler.post { kickLoop() }
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
        if (instance === this) instance = null
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
        if (clickMatchingNode(root, words, idWords, byIdOrDesc = true) ||
            clickMatchingNode(root, words, idWords, byIdOrDesc = false)
        ) return true
        // بديل أخير للاتصال والإنهاء: بعض تطبيقات الهاتف زرها أيقونة بلا نص
        // ولا وصف — اضغط أكبر زر في أسفل منتصف الشاشة (زر الاتصال/الإنهاء).
        // لا يُستخدم للرد حتى لا يُضغط زر الرفض بالخطأ.
        if (action == CallPrefs.PENDING_CALL || action == CallPrefs.PENDING_END) {
            if (clickLargestBottomButton(root)) return true
        }
        return false
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

    /**
     * أكبر زر قابل للضغط في أسفل منتصف الشاشة — في شاشة الاتصال يكون زر
     * الاتصال، وفي شاشة المكالمة يكون زر الإنهاء.
     */
    private fun clickLargestBottomButton(root: AccessibilityNodeInfo): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val rect = android.graphics.Rect()
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val className = node.className?.toString().orEmpty()
            val looksLikeButton = node.isClickable &&
                (className.endsWith("Button") ||
                    className.endsWith("ImageButton") ||
                    className.contains("FloatingActionButton"))
            if (looksLikeButton && node.isEnabled && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val centerX = rect.centerX()
                val centerY = rect.centerY()
                val area = rect.width() * rect.height()
                val inBottomCenter = centerX > width * 0.25 && centerX < width * 0.75 &&
                    centerY > height * 0.5
                if (inBottomCenter && area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return best != null && performClick(best)
    }

    companion object {
        @Volatile
        var instance: CallAccessibilityService? = null

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
