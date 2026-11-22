package com.vehicleaudit.adapters.file

import com.vehicleaudit.adapters.spi.CollectRequest
import com.vehicleaudit.adapters.spi.VehicleDataAdapter
import com.vehicleaudit.core.domain.EcuReading
import com.vehicleaudit.core.domain.EcuStatus
import com.vehicleaudit.core.domain.MileageEvent
import com.vehicleaudit.core.domain.SourceKind
import com.vehicleaudit.core.domain.TrustLevel
import com.vehicleaudit.core.domain.VehicleCase
import com.vehicleaudit.core.engine.Fingerprint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * 案卷 JSON 导入适配器。
 *
 * 用途：
 * 1. 真实诊断适配器在现场采集后，可统一导出为本格式再交引擎鉴定
 * 2. 历史维保/年检数据经渠道对接后按本格式归档
 * 3. 支持把原始报文/单据文件放在 artifacts 目录，导入时重新计算 SHA-256
 *    并与案卷中声明的哈希比对——哈希不符直接拒绝导入，防止案卷被篡改后送检
 *
 * JSON 中 artifact 字段为相对 artifacts 目录的文件路径；不提供 artifact 时
 * （如厂家 API 直采且只留存响应哈希），仅做格式校验。
 */
class JsonCaseAdapter(
    private val artifactsDir: Path? = null
) : VehicleDataAdapter {

    override val adapterId = "file/json-case-v1"

    override fun supports(make: String, model: String): Boolean = true

    @Serializable
    private data class EcuReadingDto(
        val ecuId: String,
        val sourceKind: SourceKind,
        val trustLevel: TrustLevel,
        val ecuStatus: EcuStatus = EcuStatus.UNKNOWN,
        val odometerKm: Long? = null,
        val operatingHours: Double? = null,
        val extraCounters: Map<String, Long> = emptyMap(),
        val readAtEpoch: Long,
        val rawPayloadHash: String,
        val artifact: String? = null,
        val notes: String = ""
    )

    @Serializable
    private data class MileageEventDto(
        val eventId: String,
        val sourceKind: SourceKind,
        val trustLevel: TrustLevel,
        val recordedAtEpoch: Long,
        val odometerKm: Long,
        val documentHash: String,
        val artifact: String? = null,
        val verified: Boolean = false,
        val summary: String = ""
    )

    @Serializable
    private data class CaseDto(
        val caseId: String,
        val vin: String,
        val make: String,
        val model: String,
        val modelYear: Int,
        val displayedOdometerKm: Long,
        val collectedAtEpoch: Long,
        val assumedAverageSpeedKmh: Double = 35.0,
        val ecuReadings: List<EcuReadingDto> = emptyList(),
        val historyEvents: List<MileageEventDto> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** SPI 入口：本适配器以文件为输入，此方法仅做直通封装，主入口见 [load]。 */
    override fun collect(request: CollectRequest): VehicleCase =
        throw UnsupportedOperationException("JsonCaseAdapter 请使用 load(path) 导入案卷文件")

    fun load(path: Path): VehicleCase {
        val dto = json.decodeFromString(CaseDto.serializer(), Files.readString(path))
        val readings = dto.ecuReadings.map { it.toDomain() }
        val events = dto.historyEvents.map { it.toDomain() }
        return VehicleCase(
            caseId = dto.caseId,
            vin = dto.vin,
            make = dto.make,
            model = dto.model,
            modelYear = dto.modelYear,
            displayedOdometerKm = dto.displayedOdometerKm,
            collectedAtEpoch = dto.collectedAtEpoch,
            ecuReadings = readings,
            historyEvents = events,
            assumedAverageSpeedKmh = dto.assumedAverageSpeedKmh
        )
    }

    private fun verifyArtifact(artifact: String?, declaredHash: String, owner: String) {
        if (artifact == null) return
        val base = artifactsDir
            ?: throw IllegalStateException("证据 $owner 声明了 artifact=$artifact 但未配置 artifactsDir")
        val file = base.resolve(artifact).normalize()
        if (!file.startsWith(base.normalize())) {
            throw IllegalArgumentException("证据 $owner 的 artifact 路径越界: $artifact")
        }
        if (!Files.exists(file)) throw IllegalArgumentException("证据 $owner 的原始文件缺失: $artifact")
        val actual = Fingerprint.sha256Hex(file.readBytes())
        require(actual == declaredHash) {
            "证据 $owner 的原始文件哈希与案卷声明不符（声明 $declaredHash，实际 $actual），案卷可能被篡改，拒绝导入"
        }
    }

    private fun EcuReadingDto.toDomain(): EcuReading {
        verifyArtifact(artifact, rawPayloadHash, ecuId)
        return EcuReading(
            ecuId = ecuId,
            sourceKind = sourceKind,
            trustLevel = trustLevel,
            ecuStatus = ecuStatus,
            odometerKm = odometerKm,
            operatingHours = operatingHours,
            extraCounters = extraCounters,
            readAtEpoch = readAtEpoch,
            rawPayloadHash = rawPayloadHash,
            notes = notes
        )
    }

    private fun MileageEventDto.toDomain(): MileageEvent {
        verifyArtifact(artifact, documentHash, eventId)
        return MileageEvent(
            eventId = eventId,
            sourceKind = sourceKind,
            trustLevel = trustLevel,
            recordedAtEpoch = recordedAtEpoch,
            odometerKm = odometerKm,
            documentHash = documentHash,
            verified = verified,
            summary = summary
        )
    }
}
