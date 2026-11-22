package com.vehicleaudit.core.engine

import com.vehicleaudit.core.domain.EcuReading
import com.vehicleaudit.core.domain.EcuStatus
import com.vehicleaudit.core.domain.MileageEvent
import com.vehicleaudit.core.domain.SourceKind
import com.vehicleaudit.core.domain.TrustLevel
import com.vehicleaudit.core.domain.VehicleCase

/** 测试构造夹具：集中管理固定时间戳与合法哈希，避免样板代码。 */
object CaseFixtures {
    const val T0 = 1_577_836_800_000L // 2020-01-01
    const val T1 = 1_609_459_200_000L // 2021-01-01
    const val T2 = 1_640_995_200_000L // 2022-01-01
    const val T3 = 1_672_531_200_000L // 2023-01-01
    const val T_NOW = 1_704_067_200_000L // 2024-01-01

    private var hashSeq = 0
    fun hash(): String {
        hashSeq++
        return "%064x".format(hashSeq.toBigInteger())
    }

    fun ecu(
        ecuId: String,
        kind: SourceKind,
        trust: TrustLevel,
        odometerKm: Long? = null,
        hours: Double? = null,
        status: EcuStatus = EcuStatus.ORIGINAL,
        extra: Map<String, Long> = emptyMap(),
        notes: String = "",
        at: Long = T_NOW
    ) = EcuReading(
        ecuId = ecuId,
        sourceKind = kind,
        trustLevel = trust,
        ecuStatus = status,
        odometerKm = odometerKm,
        operatingHours = hours,
        extraCounters = extra,
        readAtEpoch = at,
        rawPayloadHash = hash(),
        notes = notes
    )

    fun event(
        id: String,
        kind: SourceKind,
        trust: TrustLevel,
        at: Long,
        km: Long,
        verified: Boolean = false
    ) = MileageEvent(
        eventId = id,
        sourceKind = kind,
        trustLevel = trust,
        recordedAtEpoch = at,
        odometerKm = km,
        documentHash = hash(),
        verified = verified
    )

    fun case(
        displayed: Long,
        readings: List<EcuReading> = emptyList(),
        events: List<MileageEvent> = emptyList(),
        avgSpeed: Double = 35.0
    ) = VehicleCase(
        caseId = "CASE-TEST",
        vin = "LSGAB52L7DF000000",
        make = "测试品牌",
        model = "测试车型",
        modelYear = 2020,
        displayedOdometerKm = displayed,
        collectedAtEpoch = T_NOW,
        ecuReadings = readings,
        historyEvents = events,
        assumedAverageSpeedKmh = avgSpeed
    )

    private val fixedClockEngine = AuditEngine(clockMillis = { T_NOW })

    fun audit(case: VehicleCase) = fixedClockEngine.evaluate(case)
}
