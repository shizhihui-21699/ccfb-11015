package com.vehicleaudit.adapters.spi

import com.vehicleaudit.core.domain.VehicleCase

/**
 * 采集适配器 SPI。
 *
 * 每个品牌/渠道实现一个 Adapter：
 * - 诊断类（VAG、BMW、Toyota…）：内部走 UDS/OBD 或厂家私有协议，
 *   把原始 DID/快照映射为标准 EcuReading，rawPayloadHash 必须对原始报文计算
 * - 记录类（维保 API、年检接口、保险平台）：把外部数据映射为 MileageEvent
 *
 * 引擎只依赖 VehicleCase 标准模型，因此新增品牌不需要改动 core。
 */
interface VehicleDataAdapter {
    /** 适配器标识，写入采集日志用于追责 */
    val adapterId: String

    /** 支持的品牌/车型线索，供工厂选择 */
    fun supports(make: String, model: String): Boolean

    /** 执行采集并组装标准案卷。实现方须保证可重入、无副作用外溢 */
    fun collect(request: CollectRequest): VehicleCase
}

/**
 * 采集请求。真实诊断实现还会携带 VCI 设备句柄、安全访问凭据等，
 * 这里保持最小集，渠道凭据由实现方自行扩展。
 */
data class CollectRequest(
    val vin: String,
    val make: String,
    val model: String,
    val modelYear: Int,
    val displayedOdometerKm: Long,
    /** 采集时刻由调用方统一注入，避免各适配器时钟漂移 */
    val collectedAtEpoch: Long
)
