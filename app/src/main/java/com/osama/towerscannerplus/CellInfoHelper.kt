package com.osama.towerscannerplus

import android.content.Context
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma

data class CellRow(
    val typeLabel: String,
    val operator: String,
    val signalText: String,
    val details: String,
    val badge: String,
    val key: String?,
    val isServing: Boolean,
    val bigIdLabel: String,
    val bigIdValue: String,
    val quality: String,
    val dbm: Int,
    /** مستوى قوة الإشارة من 0 إلى 4 لأشرطة العرض. */
    val signalLevel: Int = 0
)

object CellInfoHelper {

    /** مفتاح فريد ثابت للخلية يستخدم للمقارنة مع البرج المثبّت. */
    fun cellKey(info: CellInfo): String? {
        val id = info.cellIdentity ?: return null
        return when (id) {
            is CellIdentityLte -> if (id.ci != Int.MAX_VALUE) "LTE:${id.ci}:${id.tac}" else null
            is CellIdentityNr -> if (id.nci != Long.MAX_VALUE) "NR:${id.nci}:${id.tac}" else null
            is CellIdentityGsm -> if (id.cid != Int.MAX_VALUE) "GSM:${id.cid}:${id.lac}" else null
            is CellIdentityWcdma -> if (id.cid != Int.MAX_VALUE) "WCDMA:${id.cid}:${id.lac}" else null
            is CellIdentityCdma -> "CDMA:${id.basestationId}:${id.networkId}:${id.systemId}"
            is CellIdentityTdscdma -> if (id.cid != Int.MAX_VALUE) "TD:${id.cid}:${id.lac}" else null
            else -> null
        }
    }

    fun servingCell(cells: List<CellInfo>?): CellInfo? {
        return cells?.firstOrNull { it.isRegistered }
    }

    fun describe(context: Context, info: CellInfo): CellRow {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val dbm = ss?.dbm ?: Int.MIN_VALUE
        // MCC/MNC واسم المشغّل دوال موجودة على معرّفات الخلايا الفرعية فقط
        // (CellIdentityLte/Gsm/Wcdma/Nr/Tdscdma) وليست على CellIdentity الأساسي،
        // وهي من API 28، لذلك تُقرأ بعد فحص النوع وفحص إصدار النظام.
        var operator = ""
        var mcc: String? = null
        var mnc: String? = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            when (id) {
                is CellIdentityLte -> { operator = id.operatorAlphaLong.toString(); mcc = id.mccString; mnc = id.mncString }
                is CellIdentityNr -> { operator = id.operatorAlphaLong.toString(); mcc = id.mccString; mnc = id.mncString }
                is CellIdentityGsm -> { operator = id.operatorAlphaLong.toString(); mcc = id.mccString; mnc = id.mncString }
                is CellIdentityWcdma -> { operator = id.operatorAlphaLong.toString(); mcc = id.mccString; mnc = id.mncString }
                is CellIdentityTdscdma -> { operator = id.operatorAlphaLong.toString(); mcc = id.mccString; mnc = id.mncString }
                else -> {}
            }
        }

        var type = context.getString(R.string.type_unknown)
        var bigLabel = "Cell ID"
        var bigValue = "-"
        var details = ""
        when (id) {
            is CellIdentityLte -> {
                type = context.getString(R.string.type_4g)
                bigLabel = "Cell ID (LTE)"
                bigValue = if (id.ci != Int.MAX_VALUE) id.ci.toString() else "-"
                val s = ss as? CellSignalStrengthLte
                details = "CID: ${fmt(id.ci)} | TAC: ${fmt(id.tac)} | PCI: ${fmt(id.pci)} | " +
                    "EARFCN: ${fmt(id.earfcn)} | RSRP: ${s?.rsrp?.let { "$it dBm" } ?: "-"} | " +
                    "RSRQ: ${s?.rsrq?.let { "$it dB" } ?: "-"} | SINR: ${s?.rssnr?.let { "$it dB" } ?: "-"} | " +
                    "TA: ${s?.timingAdvance?.toString() ?: "-"}"
            }
            is CellIdentityNr -> {
                type = context.getString(R.string.type_5g)
                bigLabel = "Cell ID (5G NR)"
                bigValue = if (id.nci != Long.MAX_VALUE) id.nci.toString() else "-"
                val s = ss as? CellSignalStrengthNr
                details = "NCI: ${fmtL(id.nci)} | TAC: ${fmt(id.tac)} | PCI: ${fmt(id.pci)} | " +
                    "NR-ARFCN: ${fmt(id.nrarfcn)} | SS-RSRP: ${s?.ssRsrp?.let { "$it dBm" } ?: "-"} | " +
                    "SS-RSRQ: ${s?.ssRsrq?.let { "$it dB" } ?: "-"} | SS-SINR: ${s?.ssSinr?.let { "$it dB" } ?: "-"}"
            }
            is CellIdentityGsm -> {
                type = context.getString(R.string.type_2g)
                bigLabel = "Cell ID (2G)"
                bigValue = if (id.cid != Int.MAX_VALUE) id.cid.toString() else "-"
                details = "CID: ${fmt(id.cid)} | LAC: ${fmt(id.lac)} | ARFCN: ${fmt(id.arfcn)} | " +
                    "BSIC: ${fmt(id.bsic)}"
            }
            is CellIdentityWcdma -> {
                type = context.getString(R.string.type_3g)
                bigLabel = "Cell ID (3G)"
                bigValue = if (id.cid != Int.MAX_VALUE) id.cid.toString() else "-"
                details = "CID: ${fmt(id.cid)} | LAC: ${fmt(id.lac)} | UARFCN: ${fmt(id.uarfcn)} | " +
                    "PSC: ${fmt(id.psc)}"
            }
            is CellIdentityCdma -> {
                type = context.getString(R.string.type_cdma)
                bigLabel = "Base Station Id (CDMA)"
                bigValue = id.basestationId.toString()
                val s = ss as? CellSignalStrengthCdma
                details = "BID: ${id.basestationId} | NID: ${id.networkId} | SID: ${id.systemId} | " +
                    "CDMA: ${s?.cdmaDbm?.let { "$it dBm" } ?: "-"}"
            }
            is CellIdentityTdscdma -> {
                type = context.getString(R.string.type_tdscdma)
                bigLabel = "Cell ID (TD-SCDMA)"
                bigValue = if (id.cid != Int.MAX_VALUE) id.cid.toString() else "-"
                details = "CID: ${fmt(id.cid)} | LAC: ${fmt(id.lac)} | UARFCN: ${fmt(id.uarfcn)}"
            }
        }
        val opLine = buildString {
            if (operator.isNotBlank()) append(operator)
            if (mcc != null && mnc != null) {
                if (isNotEmpty()) append("  |  ")
                append("MCC $mcc  |  MNC $mnc")
            }
        }
        val signalText = if (dbm != Int.MIN_VALUE) "$dbm dBm" else "-"
        val quality = qualityLabel(dbm)
        val level = signalLevel(dbm)
        val badge = if (info.isRegistered) context.getString(R.string.serving)
            else context.getString(R.string.neighbor)
        return CellRow(
            typeLabel = type,
            operator = opLine,
            signalText = signalText,
            details = details,
            badge = badge,
            key = cellKey(info),
            isServing = info.isRegistered,
            bigIdLabel = bigLabel,
            bigIdValue = bigValue,
            quality = quality,
            dbm = dbm,
            signalLevel = level
        )
    }

    private fun fmt(v: Int): String = if (v == Int.MAX_VALUE) "-" else v.toString()
    private fun fmtL(v: Long): String = if (v == Long.MAX_VALUE) "-" else v.toString()

    fun qualityLabel(dbm: Int): String {
        if (dbm == Int.MIN_VALUE) return "-"
        return when {
            dbm >= -70 -> "ممتازة"
            dbm >= -85 -> "جيدة جداً"
            dbm >= -95 -> "جيدة"
            dbm >= -105 -> "متوسطة"
            dbm >= -115 -> "ضعيفة"
            else -> "ضعيفة جداً"
        }
    }

    /** مستوى قوة الإشارة من 0 إلى 4 حسب dBm. */
    fun signalLevel(dbm: Int): Int {
        if (dbm == Int.MIN_VALUE) return 0
        return when {
            dbm >= -70 -> 4
            dbm >= -85 -> 3
            dbm >= -95 -> 2
            dbm >= -105 -> 1
            else -> 0
        }
    }

    /** ترتيب الخلايا: المتصلة أولاً ثم الأقوى إشارة. */
    fun sortCells(rows: List<CellRow>): List<CellRow> {
        return rows.sortedWith(
            compareByDescending<CellRow> { it.isServing }
                .thenByDescending { it.dbm }
        )
    }
}
