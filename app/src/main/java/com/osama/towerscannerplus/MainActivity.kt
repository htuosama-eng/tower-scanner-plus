package com.osama.towerscannerplus

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var telephony: TelephonyManager
    private lateinit var prefs: CallPrefs
    private val handler = Handler(Looper.getMainLooper())
    private val rows = ArrayList<CellRow>()
    private lateinit var adapter: CellAdapter

    private var prevServingKey: String? = null
    private var lastChangeWall: Long = 0L
    private var lastUpdateWall: Long = 0L
    private var autoTick = false

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!autoTick) return
            refreshCells()
            handler.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // الواجهة فاتحة دائماً مثل التطبيق الأصلي، حتى لو كان الهاتف على الوضع الداكن.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = CallPrefs(this)
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        adapter = CellAdapter()
        findViewById<ListView>(R.id.list).adapter = adapter

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshCells() }
        findViewById<Button>(R.id.btnOperator).setOnClickListener { openOperatorSettings() }
        findViewById<Button>(R.id.btnInfo).setOnClickListener { showInfo() }
        findViewById<Button>(R.id.btnCallSettings).setOnClickListener {
            startActivity(Intent(this, CallSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.tvAccessWarning).setOnClickListener {
            startActivity(Intent(this, CallSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnPinTower).setOnClickListener { pinServingTower() }
        findViewById<ToggleButton>(R.id.tglAuto).setOnCheckedChangeListener { _, checked ->
            autoTick = checked
            handler.removeCallbacks(autoRunnable)
            if (checked) handler.post(autoRunnable)
        }
        // التحديث التلقائي يعمل من نفسه منذ فتح التطبيق، كل ثانية، دون زر يدوي.
        findViewById<ToggleButton>(R.id.tglAuto).isChecked = true

        if (!hasPermissions()) {
            showPermissionScreen()
        } else {
            refreshCells()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePinnedText()
        updateAccessWarning()
        if (hasPermissions()) refreshCells()
        if (autoTick) {
            handler.removeCallbacks(autoRunnable)
            handler.post(autoRunnable)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoRunnable)
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        return (fine || coarse) && phone
    }

    private fun showPermissionScreen() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.need_permission))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    ),
                    REQ_PERM
                )
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (hasPermissions()) refreshCells() else showPermissionScreen()
        }
    }

    private fun refreshCells() {
        if (!hasPermissions()) {
            findViewById<TextView>(R.id.status).text = getString(R.string.need_permission)
            return
        }
        val cells: List<CellInfo>? = try {
            telephony.allCellInfo
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
        if (cells.isNullOrEmpty()) {
            findViewById<TextView>(R.id.status).text = getString(R.string.no_cells)
            rows.clear()
            adapter.notifyDataSetChanged()
            return
        }
        val described = cells.map { CellInfoHelper.describe(this, it) }
        val sorted = CellInfoHelper.sortCells(described)
        rows.clear()
        rows.addAll(sorted)
        adapter.notifyDataSetChanged()
        updateServingCard(sorted.firstOrNull { it.isServing })
        lastUpdateWall = System.currentTimeMillis()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        findViewById<TextView>(R.id.last_update).text =
            "${getString(R.string.last_update)}: ${fmt.format(Date(lastUpdateWall))}"
        findViewById<TextView>(R.id.status).text =
            "${getString(R.string.serving_now)}: ${sorted.count { it.isServing }} — ${cells.size} خلية"
    }

    private fun updateServingCard(serving: CellRow?) {
        val bigLabel = findViewById<TextView>(R.id.bigIdLabel)
        val bigValue = findViewById<TextView>(R.id.bigIdValue)
        val srvLabel = findViewById<TextView>(R.id.srvLabel)
        val srvLte = findViewById<TextView>(R.id.srvLte)
        val srvFields = findViewById<TextView>(R.id.srvFields)
        val srvSignal = findViewById<TextView>(R.id.srvSignal)
        val srvChange = findViewById<TextView>(R.id.srvChange)
        val bars = listOf(
            findViewById<View>(R.id.bar1),
            findViewById<View>(R.id.bar2),
            findViewById<View>(R.id.bar3),
            findViewById<View>(R.id.bar4)
        )
        if (serving == null) {
            bigLabel.text = "Cell ID"
            bigValue.text = "-"
            srvLabel.text = getString(R.string.no_serving)
            srvLte.text = "-"
            srvFields.text = "-"
            srvSignal.text = "-"
            bars.forEach { it.setBackgroundColor(getColor(R.color.bar_off)) }
            srvChange.text = "${getString(R.string.last_tower_change)}: ${getString(R.string.never_changed)}"
            return
        }
        if (prevServingKey != null && prevServingKey != serving.key) {
            lastChangeWall = System.currentTimeMillis()
        }
        prevServingKey = serving.key
        bigLabel.text = serving.bigIdLabel
        bigValue.text = serving.bigIdValue
        srvLabel.text = "${serving.typeLabel} — ${serving.operator}"
        srvLte.text = serving.details
        srvFields.text = serving.details
        srvSignal.text = "${getString(R.string.signal_strength)}: ${serving.signalText} (${serving.quality})"
        val onColor = getColor(R.color.green)
        val offColor = getColor(R.color.bar_off)
        bars.forEachIndexed { index, bar ->
            bar.setBackgroundColor(if (index < serving.signalLevel) onColor else offColor)
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        srvChange.text = if (lastChangeWall == 0L) {
            "${getString(R.string.last_tower_change)}: ${getString(R.string.never_changed)}"
        } else {
            "${getString(R.string.last_tower_change)}: ${fmt.format(Date(lastChangeWall))}"
        }
    }

    private fun pinServingTower() {
        val serving = rows.firstOrNull { it.isServing }
        if (serving == null || serving.key == null) {
            Toast.makeText(this, getString(R.string.no_serving_to_pin), Toast.LENGTH_SHORT).show()
            return
        }
        prefs.pinnedTowerKey = serving.key
        prefs.pinnedTowerLabel = "${serving.typeLabel} — ${serving.bigIdLabel}: ${serving.bigIdValue}"
        updatePinnedText()
        Toast.makeText(this, getString(R.string.tower_pinned), Toast.LENGTH_SHORT).show()
    }

    /** تنبيه أحمر في الشاشة الرئيسية إن كانت الأتمتة مفعّلة وخدمة الوصول متوقفة. */
    private fun updateAccessWarning() {
        val warning = findViewById<TextView>(R.id.tvAccessWarning)
        val answerOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
        val show = prefs.automationEnabled && !answerOk && !CallPrefs.isAccessibilityEnabled(this)
        warning.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updatePinnedText() {
        val label = prefs.pinnedTowerLabel
        findViewById<TextView>(R.id.pinnedTowerText).text =
            if (label.isNullOrEmpty()) getString(R.string.no_pinned_tower)
            else "${getString(R.string.pinned_tower)}: $label"
    }

    private fun openOperatorSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.unavailable), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_body))
            .setPositiveButton(getString(R.string.about_ok), null)
            .show()
    }

    inner class CellAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.row_cell, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.rowType).text = row.typeLabel
            view.findViewById<TextView>(R.id.rowBigId).text = "${row.bigIdLabel}: ${row.bigIdValue}"
            view.findViewById<TextView>(R.id.rowOperator).text = row.operator
            view.findViewById<TextView>(R.id.rowSignal).text =
                "${row.signalText} (${row.quality})"
            view.findViewById<TextView>(R.id.rowDetails).text = row.details
            view.findViewById<TextView>(R.id.rowBadge).text = row.badge
            return view
        }
    }

    companion object {
        const val REQ_PERM = 1001
        /** التحديث التلقائي كل ثانية. */
        const val AUTO_INTERVAL_MS = 1000L
    }
}
