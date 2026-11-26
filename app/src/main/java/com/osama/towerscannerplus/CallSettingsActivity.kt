package com.osama.towerscannerplus

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CallSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: CallPrefs
    private lateinit var telephony: TelephonyManager
    private var updatingSwitch = false
    private var pendingAutomation = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(CallPrefs.EXTRA_STATUS) ?: return
            findViewById<TextView>(R.id.tvStatus).text = text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_settings)
        prefs = CallPrefs(this)
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        findViewById<EditText>(R.id.etNumber).setText(prefs.number)
        findViewById<EditText>(R.id.etOutDuration).setText(prefs.outDurationSec.toString())
        findViewById<EditText>(R.id.etInDuration).setText(prefs.inDurationSec.toString())

        findViewById<Button>(R.id.btnSaveNumber).setOnClickListener {
            val number = findViewById<EditText>(R.id.etNumber).text.toString().trim()
            if (number.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_number_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.number = number
            Toast.makeText(this, getString(R.string.number_saved), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSaveDurations).setOnClickListener {
            val out = findViewById<EditText>(R.id.etOutDuration).text.toString().toIntOrNull()
            val inn = findViewById<EditText>(R.id.etInDuration).text.toString().toIntOrNull()
            if (out == null || inn == null || out <= 0 || inn <= 0) {
                Toast.makeText(this, "أدخل عدد ثوانٍ صحيحاً أكبر من صفر", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.outDurationSec = out
            prefs.inDurationSec = inn
            Toast.makeText(this, getString(R.string.durations_saved), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnPinTower).setOnClickListener { pinCurrentTower() }
        findViewById<Button>(R.id.btnUnpinTower).setOnClickListener {
            prefs.pinnedTowerKey = null
            prefs.pinnedTowerLabel = null
            refreshPinnedText()
            Toast.makeText(this, getString(R.string.tower_unpinned), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.unavailable), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Switch>(R.id.swAutomation).setOnCheckedChangeListener { _, checked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            try {
                prefs.automationEnabled = checked
                if (checked) {
                    if (!ensureAutomationPermissions()) {
                        pendingAutomation = true
                        updatingSwitch = true
                        findViewById<Switch>(R.id.swAutomation).isChecked = false
                        updatingSwitch = false
                        prefs.automationEnabled = false
                        return@setOnCheckedChangeListener
                    }
                    startAutomationService()
                    findViewById<TextView>(R.id.tvStatus).text = getString(R.string.automation_running)
                    // تنبيه واضح مرة واحدة: بدون خدمة الوصول لن يُضغط زر الاتصال.
                    if (!CallPrefs.isAccessibilityEnabled(this)) {
                        showAccessibilityDialog()
                    }
                } else {
                    pendingAutomation = false
                    stopAutomationService()
                    findViewById<TextView>(R.id.tvStatus).text = getString(R.string.automation_stopped)
                }
            } catch (_: Exception) {
                // لا يُغلق التطبيق أبداً بسبب مفتاح الأتمتة: أظهر رسالة وأعد المفتاح.
                pendingAutomation = false
                updatingSwitch = true
                findViewById<Switch>(R.id.swAutomation).isChecked = false
                updatingSwitch = false
                prefs.automationEnabled = false
                Toast.makeText(this, getString(R.string.automation_start_failed), Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Switch>(R.id.swRepeat).setOnCheckedChangeListener { _, checked ->
            prefs.autoRepeat = checked
        }

        findViewById<Button>(R.id.btnCallNow).setOnClickListener {
            val intent = Intent(this, CallAutomationService::class.java)
            intent.action = CallAutomationService.ACTION_CALL_NOW
            startServiceCompat(intent)
        }

        findViewById<Button>(R.id.btnTestCall).setOnClickListener { testAutoCall() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.automationEnabled = false
            updatingSwitch = true
            findViewById<Switch>(R.id.swAutomation).isChecked = false
            updatingSwitch = false
            stopAutomationService()
            findViewById<TextView>(R.id.tvStatus).text = getString(R.string.automation_stopped)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPinnedText()
        refreshAccessibilityText()
        updatingSwitch = true
        findViewById<Switch>(R.id.swAutomation).isChecked = prefs.automationEnabled
        findViewById<Switch>(R.id.swRepeat).isChecked = prefs.autoRepeat
        updatingSwitch = false
        val last = CallAutomationService.lastStatus
        if (last.isNotEmpty()) findViewById<TextView>(R.id.tvStatus).text = last
        // تنبيه واضح: الأتمتة مفعّلة لكن خدمة الوصول متوقفة فلن يُضغط زر الاتصال.
        if (prefs.automationEnabled && !CallPrefs.isAccessibilityEnabled(this)) {
            findViewById<TextView>(R.id.tvStatus).text = getString(R.string.need_accessibility)
        }
        val filter = IntentFilter(CallPrefs.ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL_PERM && pendingAutomation && hasAutomationPermissions()) {
            pendingAutomation = false
            findViewById<Switch>(R.id.swAutomation).isChecked = true
        }
    }

    private fun refreshPinnedText() {
        val label = prefs.pinnedTowerLabel
        findViewById<TextView>(R.id.tvPinnedTower).text =
            if (label.isNullOrEmpty()) getString(R.string.no_pinned_tower)
            else "${getString(R.string.pinned_tower)}: $label"
    }

    private fun refreshAccessibilityText() {
        val on = CallPrefs.isAccessibilityEnabled(this)
        findViewById<TextView>(R.id.tvAccessibilityStatus).text =
            if (on) getString(R.string.accessibility_on) else getString(R.string.accessibility_off)
    }

    private fun pinCurrentTower() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE
                ),
                REQ_PERM
            )
            return
        }
        val cells: List<CellInfo>? = try {
            telephony.allCellInfo
        } catch (_: Exception) {
            null
        }
        val serving = CellInfoHelper.servingCell(cells)
        if (serving == null) {
            Toast.makeText(this, getString(R.string.no_serving_to_pin), Toast.LENGTH_SHORT).show()
            return
        }
        val row = CellInfoHelper.describe(this, serving)
        val key = CellInfoHelper.cellKey(serving)
        if (key == null) {
            Toast.makeText(this, getString(R.string.no_serving_to_pin), Toast.LENGTH_SHORT).show()
            return
        }
        prefs.pinnedTowerKey = key
        prefs.pinnedTowerLabel = "${row.typeLabel} — ${row.bigIdLabel}: ${row.bigIdValue}"
        refreshPinnedText()
        Toast.makeText(this, getString(R.string.tower_pinned), Toast.LENGTH_SHORT).show()
    }

    /** الأتمتة تحتاج فقط الموقع وحالة الهاتف — لا إذن اتصال ولا تطبيق افتراضي. */
    private fun hasAutomationPermissions(): Boolean {
        val loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        return loc && phone
    }

    private fun ensureAutomationPermissions(): Boolean {
        if (hasAutomationPermissions()) return true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            ),
            REQ_CALL_PERM
        )
        Toast.makeText(this, getString(R.string.need_permission), Toast.LENGTH_SHORT).show()
        return false
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.accessibility_dialog_title))
            .setMessage(getString(R.string.accessibility_dialog_message))
            .setPositiveButton(getString(R.string.enable_accessibility_now)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.unavailable), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.later), null)
            .show()
    }

    /**
     * اختبار الضغط التلقائي: يفتح شاشة الاتصال بالرقم المحفوظ ويحاول ضغط
     * زر الاتصال، ثم يعرض النتيجة بعد 6 ثوانٍ (مكالمة حقيقية بالرقم المحفوظ).
     */
    private fun testAutoCall() {
        val number = prefs.number.trim()
        if (number.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_number_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (!CallPrefs.isAccessibilityEnabled(this)) {
            showAccessibilityDialog()
            return
        }
        prefs.lastClickResult = ""
        prefs.setPending(CallPrefs.PENDING_CALL, 20000L)
        try {
            startService(Intent(this, CallAccessibilityService::class.java))
        } catch (_: Exception) {
        }
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<TextView>(R.id.tvStatus).text = getString(R.string.dialing)
        Handler(Looper.getMainLooper()).postDelayed({
            val result = prefs.lastClickResult
            val text = if (result == "CALL:ok") {
                getString(R.string.test_click_ok)
            } else {
                getString(R.string.test_click_failed)
            }
            findViewById<TextView>(R.id.tvStatus).text = text
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }, 6000L)
    }

    private fun startAutomationService() {
        val intent = Intent(this, CallAutomationService::class.java)
        startServiceCompat(intent)
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.automation_start_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAutomationService() {
        val intent = Intent(this, CallAutomationService::class.java)
        intent.action = CallAutomationService.ACTION_STOP
        try {
            startService(intent)
        } catch (_: Exception) {
            try {
                stopService(Intent(this, CallAutomationService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val REQ_PERM = 1002
        const val REQ_CALL_PERM = 1003
    }
}
