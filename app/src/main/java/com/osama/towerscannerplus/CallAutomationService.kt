package com.osama.towerscannerplus

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.CellInfo
import androidx.core.content.ContextCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

/**
 * خدمة أتمتة الاتصال حسب البرج المثبّت.
 *
 * - تتصل بالرقم المحفوظ فقط أثناء وجود الهاتف على البرج المثبّت.
 * - بعد الرد، تبقى المكالمة الصادرة مدة outDurationSec ثم تفصل وتعيد الاتصال.
 * - إذا تغيّر البرج أثناء المكالمة، تفصل فوراً، وتعيد الاتصال عند العودة للبرج المثبّت.
 * - المكالمة الواردة من الرقم نفسه: لا تُجاب إلا على البرج المثبّت، وتبقى مدة inDurationSec ثم تفصل.
 *
 * ملاحظة أندرويد: إنهاء مكالمة أو الرد عليها برمجياً يتطلب أن يكون التطبيق
 * هو تطبيق الهاتف الافتراضي، وإلا يرفض TelecomManager الطلب باستثناء أمني.
 */
class CallAutomationService : Service() {

    private lateinit var prefs: CallPrefs
    private lateinit var telephony: TelephonyManager
    private lateinit var telecom: TelecomManager
    private val handler = Handler(Looper.getMainLooper())

    private var servingKey: String? = null
    private var registeredKeys: Set<String> = emptySet()
    private var lastCellUpdateReqMs = 0L
    private var servingLabel: String? = null
    private var callState: Int = TelephonyManager.CALL_STATE_IDLE
    private var isOurOutgoingCall = false
    private var isAcceptedIncoming = false
    private var isManagedCall = false
    private var singleCallMode = false
    private var lastDialAttemptMs = 0L
    private var lastCallEndedMs = 0L
    private var dialing = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshServingCell()
            // أمان: إن فُتح الماسح ولم يبدأ الاتصال خلال 12 ثانية، فزر الاتصال
            // لم يُضغط (غالباً خدمة الوصول غير مفعّلة): أوقف المحاولات المتكررة
            // وأخبر المستخدم بالسبب بدل إعادة فتح الماسح بلا فائدة.
            if (dialing && callState == TelephonyManager.CALL_STATE_IDLE &&
                System.currentTimeMillis() - lastDialAttemptMs > 12000L
            ) {
                dialing = false
                isOurOutgoingCall = false
                prefs.clearPending()
                prefs.dialBlockedUntilMs = System.currentTimeMillis() + 15000L
                setStatus(getString(R.string.auto_call_failed))
            }
            evaluateGate()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val endCallRunnable = Runnable {
        val reason = if (isOurOutgoingCall) {
            getString(R.string.call_ended_duration)
        } else {
            getString(R.string.call_ended_duration)
        }
        endCurrentCall()
        setStatus(reason)
        isOurOutgoingCall = false
        isAcceptedIncoming = false
        isManagedCall = false
        lastCallEndedMs = System.currentTimeMillis()
        if (!singleCallMode && prefs.automationEnabled && prefs.autoRepeat) {
            scheduleRedial()
        } else if (singleCallMode && !prefs.automationEnabled) {
            stopSelf()
        }
    }

    private val redialRunnable = Runnable {
        dialing = false
        if (prefs.automationEnabled && prefs.autoRepeat && onPinnedTower() && callState == TelephonyManager.CALL_STATE_IDLE) {
            placeOutgoingCall()
        }
    }

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCellInfoChanged(cellInfo: List<CellInfo>?) {
            updateServingFromList(cellInfo)
            evaluateGate()
        }

        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val prev = callState
            callState = state
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val target = CallPrefs.normalizeNumber(prefs.number)
                    val incoming = CallPrefs.normalizeNumber(phoneNumber)
                    if (target.isNotEmpty() && incoming.isNotEmpty() && target == incoming) {
                        if (prefs.automationEnabled && onPinnedTower()) {
                            if (acceptIncomingCall()) {
                                isAcceptedIncoming = true
                                setStatus(getString(R.string.incoming_answered))
                            } else {
                                setStatus(getString(R.string.need_answer_permission))
                            }
                        } else if (prefs.automationEnabled) {
                            setStatus(getString(R.string.incoming_ignored))
                        }
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // مكالمة بدأت: أدرها إن كانت من التطبيق أو واردة رددنا عليها،
                    // أو أي مكالمة ونحن على البرج المثبّت والأتمتة مفعّلة
                    // (مثل اتصال يدوي بالرقم المحفوظ): تنتهي بعد المدة المحددة
                    // ثم يعاد الاتصال تلقائياً.
                    val managed = isOurOutgoingCall || isAcceptedIncoming ||
                        (prefs.automationEnabled && onPinnedTower())
                    dialing = false
                    if (managed) {
                        isManagedCall = true
                        val seconds = if (isAcceptedIncoming) prefs.inDurationSec else prefs.outDurationSec
                        handler.removeCallbacks(endCallRunnable)
                        handler.postDelayed(endCallRunnable, seconds * 1000L)
                        setStatus("مكالمة جارية — ستنتهي بعد $seconds ثانية")
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (prev == TelephonyManager.CALL_STATE_IDLE && dialing &&
                        System.currentTimeMillis() - lastDialAttemptMs > 12000L) dialing = false
                    if (prev != TelephonyManager.CALL_STATE_IDLE) {
                        dialing = false
                        handler.removeCallbacks(endCallRunnable)
                        val wasOurs = isOurOutgoingCall || isAcceptedIncoming || isManagedCall
                        isOurOutgoingCall = false
                        isAcceptedIncoming = false
                        isManagedCall = false
                        lastCallEndedMs = System.currentTimeMillis()
                        if (wasOurs && !singleCallMode && prefs.automationEnabled && prefs.autoRepeat) {
                            scheduleRedial()
                        } else if (singleCallMode && !prefs.automationEnabled) {
                            stopSelf()
                        }
                    }
                }
            }
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = CallPrefs(this)
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        createChannel()
        // بدء الخدمة الأمامية محمي: أي فشل هنا كان يُغلق التطبيق كاملاً عند تفعيل الأتمتة.
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.automation_running)))
        } catch (_: Exception) {
            setStatus(getString(R.string.automation_start_failed))
        }
        try {
            @Suppress("DEPRECATION")
            telephony.listen(
                phoneListener,
                PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_CALL_STATE
            )
        } catch (_: Exception) {
            setStatus(getString(R.string.automation_start_failed))
        }
        refreshServingCell()
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.automationEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALL_NOW -> {
                singleCallMode = !prefs.automationEnabled
                if (!placeOutgoingCall()) {
                    singleCallMode = false
                }
            }
            else -> {
                singleCallMode = false
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(endCallRunnable)
        handler.removeCallbacks(redialRunnable)
        @Suppress("DEPRECATION")
        telephony.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- البرج ----------

    private fun onPinnedTower(): Boolean {
        val pinned = prefs.pinnedTowerKey ?: return false
        // في 5G NSA يكون هناك أكثر من خلية مسجّلة (LTE + NR): يكفي تطابق أي واحدة.
        return registeredKeys.contains(pinned)
    }

    private fun refreshServingCell() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            updateServingFromList(telephony.allCellInfo)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        // allCellInfo قد يعيد بيانات قديمة (مخزّنة): اطلب تحديثاً فعلياً كل 3 ثوانٍ.
        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && now - lastCellUpdateReqMs > 3000L) {
            lastCellUpdateReqMs = now
            try {
                telephony.requestCellInfoUpdate(
                    ContextCompat.getMainExecutor(this),
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            updateServingFromList(cellInfo)
                            evaluateGate()
                        }
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun updateServingFromList(cells: List<CellInfo>?) {
        if (cells.isNullOrEmpty()) return
        val registered = cells.filter { it.isRegistered }
        val keys = registered.mapNotNull { CellInfoHelper.cellKey(it) }.toSet()
        if (keys.isEmpty()) {
            if (registeredKeys.isNotEmpty()) {
                registeredKeys = emptySet()
                handleTowerChange(wasOnPinned = true)
            }
            return
        }
        val wasOnPinned = onPinnedTower()
        val pinned = prefs.pinnedTowerKey
        val primary = registered.firstOrNull { CellInfoHelper.cellKey(it) == pinned } ?: registered.first()
        val row = CellInfoHelper.describe(this, primary)
        registeredKeys = keys
        servingKey = CellInfoHelper.cellKey(primary)
        servingLabel = "${row.typeLabel} — ${row.bigIdLabel}: ${row.bigIdValue}"
        handleTowerChange(wasOnPinned)
        updateNotification()
    }

    private fun handleTowerChange(wasOnPinned: Boolean) {
        val nowOnPinned = onPinnedTower()
        if (wasOnPinned && !nowOnPinned &&
            callState == TelephonyManager.CALL_STATE_OFFHOOK && isManagedCall
        ) {
            // غادرنا البرج المثبّت أثناء المكالمة: افصل فوراً.
            handler.removeCallbacks(endCallRunnable)
            endCurrentCall()
            isOurOutgoingCall = false
            isAcceptedIncoming = false
            isManagedCall = false
            lastCallEndedMs = System.currentTimeMillis()
            setStatus(getString(R.string.call_ended_tower))
        }
        if (!wasOnPinned && nowOnPinned && prefs.automationEnabled && prefs.autoRepeat &&
            callState == TelephonyManager.CALL_STATE_IDLE
        ) {
            scheduleRedial()
        }
    }

    private fun evaluateGate() {
        if (!prefs.automationEnabled || !prefs.autoRepeat) return
        if (singleCallMode) return
        if (callState != TelephonyManager.CALL_STATE_IDLE) return
        if (dialing) return
        if (System.currentTimeMillis() < prefs.dialBlockedUntilMs) return
        if (!onPinnedTower()) return
        val now = System.currentTimeMillis()
        if (now - lastDialAttemptMs < 5000L) return
        if (now - lastCallEndedMs < 3000L && lastCallEndedMs != 0L) return
        placeOutgoingCall()
    }

    /** يوقظ خدمة الوصول لتبدأ حلقة محاولة ضغط الزر فوراً. */
    private fun kickAccessibility() {
        CallAccessibilityService.instance?.kick()
    }

    private fun scheduleRedial() {
        handler.removeCallbacks(redialRunnable)
        handler.postDelayed(redialRunnable, 3000L)
    }

    // ---------- المكالمات ----------

    private fun placeOutgoingCall(): Boolean {
        val number = prefs.number.trim()
        if (number.isEmpty()) {
            setStatus(getString(R.string.enter_number_first))
            return false
        }
        if (prefs.pinnedTowerKey.isNullOrEmpty()) {
            setStatus(getString(R.string.pin_tower_first))
            return false
        }
        if (!onPinnedTower()) {
            setStatus(getString(R.string.not_on_pinned_tower))
            return false
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("يلزم منح إذن إجراء المكالمات (CALL_PHONE) من إعدادات التطبيق")
            return false
        }
        lastDialAttemptMs = System.currentTimeMillis()
        dialing = true
        isOurOutgoingCall = true
        // الطريقة الأساسية: TelecomManager.placeCall — تعمل من الخدمة في الخلفية،
        // بخلاف startActivity(ACTION_CALL) التي يمنعها أندرويد 10+ بصمت من الخلفية.
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
            setStatus(getString(R.string.dialing))
            return true
        } catch (_: Exception) {
        }
        // احتياط: ACTION_CALL
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            setStatus(getString(R.string.dialing))
            true
        } catch (_: SecurityException) {
            dialing = false
            isOurOutgoingCall = false
            setStatus("تعذّر بدء المكالمة: تحقق من إذن إجراء المكالمات")
            false
        } catch (_: Exception) {
            dialing = false
            isOurOutgoingCall = false
            setStatus(getString(R.string.unavailable))
            false
        }
    }

    private fun hasAnswerPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private fun endCurrentCall(): Boolean {
        // الطريقة الأساسية: TelecomManager.endCall بإذن ANSWER_PHONE_CALLS
        // (لا يحتاج أن يكون التطبيق هو تطبيق الهاتف الافتراضي).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasAnswerPermission()) {
            try {
                if (telecom.endCall()) return true
            } catch (_: Exception) {
            }
        }
        // احتياط: خدمة الوصول تضغط زر الإنهاء.
        if (CallPrefs.isAccessibilityEnabled(this)) {
            prefs.setPending(CallPrefs.PENDING_END, 15000L)
            kickAccessibility()
            return true
        }
        setStatus(getString(R.string.need_answer_permission))
        return false
    }

    private fun acceptIncomingCall(): Boolean {
        if (hasAnswerPermission()) {
            try {
                telecom.acceptRingingCall()
                return true
            } catch (_: Exception) {
            }
        }
        if (CallPrefs.isAccessibilityEnabled(this)) {
            prefs.setPending(CallPrefs.PENDING_ANSWER, 25000L)
            kickAccessibility()
            return true
        }
        setStatus(getString(R.string.need_answer_permission))
        return false
    }

    // ---------- الحالة والإشعار ----------

    private fun setStatus(text: String) {
        lastStatus = text
        val intent = Intent(CallPrefs.ACTION_REFRESH)
        intent.putExtra(CallPrefs.EXTRA_STATUS, text)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        updateNotification()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.call_settings_title),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, CallSettingsActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val tower = servingLabel?.let { " | $it" } ?: ""
            nm.notify(NOTIF_ID, buildNotification(lastStatus + tower))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_STOP = "com.osama.towerscannerplus.STOP"
        const val ACTION_CALL_NOW = "com.osama.towerscannerplus.CALL_NOW"
        const val CHANNEL_ID = "call_auto"
        const val NOTIF_ID = 1001
        const val POLL_MS = 1000L
        @Volatile
        var lastStatus: String = ""
    }
}
