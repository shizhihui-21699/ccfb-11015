package com.vehicleaudit.core.domain

import kotlinx.serialization.Serializable

/**
 * 数据来源大类。决定一条证据在融合时的基准可信度。
 */
@Serializable
enum class SourceKind {
    /** 仪表盘控制单元（IC/Kombi），通常就是调表者直接改写的对象，可信度最低 */
    INSTRUMENT_CLUSTER,

    /** 动力/底盘类 ECU：发动机、变速箱、ABS/ESP、安全气囊、网关等 */
    ECU,

    /** 钥匙/车身类模块：BCM、座椅记忆、空调、钥匙记忆等，常被调表者遗漏 */
    BODY_MODULE,

    /** 远程信息处理/厂家后台遥测（T-Box、OEM 云端） */
    TELEMATICS,

    /** 4S 店/授权维修站保养维修记录 */
    SERVICE_RECORD,

    /** 年检/检测站 OBD 里程读数 */
    INSPECTION,

    /** 保险理赔记录中的里程 */
    INSURANCE,

    /** 其他历史单据（过户发票、二手车流通平台记录等） */
    PAPER_TRAIL
}

/**
 * 单条证据的可信度等级。适配器在采集时必须给出依据，引擎不允许默认高可信。
 */
@Serializable
enum class TrustLevel {
    /** 有数字签名/厂家后台直连/检测站官方接口，篡改成本极高 */
    CRYPTOGRAPHIC,

    /** 独立于仪表盘的 ECU/模块，且数据带内部一致性校验（如计数器配对） */
    HIGH,

    /** 独立模块的普通读数，或授权渠道的结构化维保记录 */
    MEDIUM,

    /** 纸面/第三方录入记录，或无法校验完整性的副本 */
    LOW,

    /** 来源不明、无法交叉验证，或该模块有维修/更换标记 */
    UNTRUSTED
}

/**
 * 控制单元的生命周期状态——用于区分"模块被换掉了"和"里程被改了"，
 * 这是降低误报率的关键：换件导致的低读数不能直接当作调表证据。
 */
@Serializable
enum class EcuStatus {
    /** 原厂件，无更换记录 */
    ORIGINAL,

    /** 有维修/更换记录（里程可能被清零或来自拆车件） */
    REPLACED_OR_REPAIRED,

    /** 状态未知 */
    UNKNOWN
}
