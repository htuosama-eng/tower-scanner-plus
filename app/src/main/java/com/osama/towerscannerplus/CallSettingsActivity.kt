package com.osama.towerscannerplus

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class CallSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: CallPrefs
    private lateinit var telephony: TelephonyManager

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(CallPrefs.EXTRA_STATUS) ?: return
            findViewById<TextView>(R.id.tvStatus).text = text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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

        findViewById<Switch>(R.id.swAutomation).setOnCheckedChangeListener { _, checked ->
            prefs.automationEnabled = checked
            if (checked) {
                if (!ensureCallPermissions()) {
                    findViewById<Switch>(R.id.swAutomation).isChecked = false
                    prefs.automationEnabled = false
                    return@setOnCheckedChangeListener
                }
                startAutomationService()
                findViewById<TextView>(R.id.tvStatus).text = getString(R.string.automation_running)
            } else {
                stopAutomationService()
                findViewById<TextView>(R.id.tvStatus).text = getString(R.string.automation_stopped)
            }
        }

        findViewById<Switch>(R.id.swRepeat).setOnCheckedChangeListener { _, checked ->
            prefs.autoRepeat = checked
        }

        findViewById<Button>(R.id.btnCallNow).setOnClickListener {
            if (!ensureCallPermissions()) return@setOnClickListener
            val intent = Intent(this, CallAutomationService::class.java)
            intent.action = CallAutomationService.ACTION_CALL_NOW
            startServiceCompat(intent)
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.automationEnabled = false
            findViewById<Switch>(R.id.swAutomation).isChecked = false
            stopAutomationService()
            findViewById<TextView>(R.id.tvStatus).text = getString(R.string.automation_stopped)
        }

        findViewById<Button>(R.id.btnDefaultDialer).setOnClickListener { requestDefaultDialer() }
    }

    override fun onResume() {
        super.onResume()
        refreshPinnedText()
        findViewById<Switch>(R.id.swAutomation).isChecked = prefs.automationEnabled
        findViewById<Switch>(R.id.swRepeat).isChecked = prefs.autoRepeat
        val last = CallAutomationService.lastStatus
        if (last.isNotEmpty()) findViewById<TextView>(R.id.tvStatus).text = last
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

    private fun refreshPinnedText() {
        val label = prefs.pinnedTowerLabel
        findViewById<TextView>(R.id.tvPinnedTower).text =
            if (label.isNullOrEmpty()) getString(R.string.no_pinned_tower)
            else "${getString(R.string.pinned_tower)}: $label"
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

    private fun ensureCallPermissions(): Boolean {
        val needed = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_CALL_PERM)
        Toast.makeText(this, getString(R.string.need_call_permission), Toast.LENGTH_SHORT).show()
        return false
    }

    private fun startAutomationService() {
        val intent = Intent(this, CallAutomationService::class.java)
        startServiceCompat(intent)
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAutomationService() {
        val intent = Intent(this, CallAutomationService::class.java)
        intent.action = CallAutomationService.ACTION_STOP
        try {
            startService(intent)
        } catch (_: Exception) {
            stopService(Intent(this, CallAutomationService::class.java))
        }
    }

    private fun requestDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                        REQ_DIALER_ROLE
                    )
                    return
                }
            }
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            intent.putExtra(
                TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                packageName
            )
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DIALER_ROLE) {
            val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
            val isDefault = tm.defaultDialerPackage == packageName
            Toast.makeText(
                this,
                if (isDefault) "تم تعيين التطبيق كتطبيق الهاتف الافتراضي"
                else "لم يتم التعيين — جرّب مرة أخرى من إعدادات الهاتف",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val REQ_PERM = 1002
        const val REQ_CALL_PERM = 1003
        const val REQ_DIALER_ROLE = 1004
    }
}
