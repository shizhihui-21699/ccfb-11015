package com.vehicleaudit.core.domain

import kotlinx.serialization.Serializable

/**
 * 从某个控制单元读出的一条里程相关数据。
 *
 * @property ecuId        适配器命名空间内的稳定标识，如 "BMW/FRM"、"VAG/TCU_DSG"
 * @property ecuStatus    该单元是否有更换/维修史
 * @property odometerKm   该单元自身存储的总里程读数（公里）；无法读取时为 null
 * @property operatingHours 工作小时数（发动机/空调/发电机常记），null 表示无此数据
 * @property extraCounters 该单元里其他可换算里程的计数器，键为计数器名，值为公里当量
 *                        例：变速箱离合器接合次数→估算里程、DPF 再生次数→估算里程
 * @property readAtEpoch  采集时刻（epoch 毫秒），由采集端注入
 * @property rawPayloadHash 原始诊断报文/文件的 SHA-256，证据链锚点
 * @param sourceKind / trustLevel 决定融合权重
 */
@Serializable
data class EcuReading(
    val ecuId: String,
    val sourceKind: SourceKind,
    val trustLevel: TrustLevel,
    val ecuStatus: EcuStatus = EcuStatus.UNKNOWN,
    val odometerKm: Long? = null,
    val operatingHours: Double? = null,
    val extraCounters: Map<String, Long> = emptyMap(),
    val readAtEpoch: Long,
    val rawPayloadHash: String,
    val notes: String = ""
) {
    init {
        require(ecuId.isNotBlank()) { "ecuId 不能为空" }
        require(rawPayloadHash.length == 64) { "rawPayloadHash 必须是 64 位 SHA-256" }
        require(odometerKm == null || odometerKm >= 0) { "里程不能为负" }
        require(operatingHours == null || operatingHours >= 0.0) { "工作小时不能为负" }
    }
}

/**
 * 一条带时间戳的历史里程事件：保养、年检、保险、过户、平台记录等。
 *
 * @property recordedAtEpoch 单据发生时间
 * @property odometerKm      单据上记录的里程
 * @property documentHash    单据原件（照片/PDF/API 响应体）的 SHA-256
 * @property verified        是否通过了渠道校验（官方 API 可查 / 单据防伪通过）
 */
@Serializable
data class MileageEvent(
    val eventId: String,
    val sourceKind: SourceKind,
    val trustLevel: TrustLevel,
    val recordedAtEpoch: Long,
    val odometerKm: Long,
    val documentHash: String,
    val verified: Boolean = false,
    val summary: String = ""
) {
    init {
        require(odometerKm >= 0) { "历史事件里程不能为负" }
        require(documentHash.length == 64) { "documentHash 必须是 64 位 SHA-256" }
    }
}

/**
 * 一次鉴定案件的全部输入。引擎只消费这个不可变对象，保证同案同判、可重放。
 */
@Serializable
data class VehicleCase(
    val caseId: String,
    val vin: String,
    val make: String,
    val model: String,
    val modelYear: Int,
    /** 仪表盘当前显示里程，即待证伪的对象 */
    val displayedOdometerKm: Long,
    /** 采集时刻 */
    val collectedAtEpoch: Long,
    val ecuReadings: List<EcuReading> = emptyList(),
    val historyEvents: List<MileageEvent> = emptyList(),
    /** 平均行驶速度假设（公里/小时），用于工作小时换算；按车型/用途可覆盖 */
    val assumedAverageSpeedKmh: Double = 35.0,
    /**
     * 采集方是否已完成模块更换史核查（调取维修履历/厂家后台比对）。
     * false 时，UNKNOWN 状态的模块按"履历不全"处理并在 R6 留痕；
     * true 时，ORIGINAL 即为经核实的原厂状态。
     */
    val replacementHistoryKnown: Boolean = true
) {
    init {
        require(vin.length in 6..20) { "VIN 长度不合法" }
        require(displayedOdometerKm >= 0) { "表显里程不能为负" }
        require(assumedAverageSpeedKmh > 0) { "平均速度假设必须为正" }
    }
}
