package com.metrostop.reminder.core.model

/**
 * 全部阈值 / 滤波参数 / 兜底时长的**唯一来源**（硬性规则 2）。
 *
 * 调参闭环：导出实测 CSV → 离线回放验证 → 新参数写回本文件 → 留一个 replay 回归用例。
 * 任何其它文件都不得再写死数值阈值；需要新阈值时在本类新增字段。
 */
data class TuningConfig(
    // ---------- 传感器 ----------
    /** 目标采样率（Hz）；TYPE_LINEAR_ACCELERATION 无此值时按 50 Hz 正常取 */
    val targetSampleHz: Int = 50,

    // ---------- 振动判据（构成滞回，总纲 5.3）----------
    /**
     * 「在动」阈值：vib 高于它视为列车仍在行驶。
     * 2026-09-28 真实车厢重标（成都 4/6 号线、口袋姿势）：巡航 vib p25≈0.10 / p50≈0.135，
     * 旧值 0.25 高于几乎全部巡航样本 → 计数后状态机永远等不到「起步」而卡死（早高峰 12 站漏 10 站）。
     */
    val vibRunTh: Float = 0.10f,
    /**
     * 「停了」阈值：vib 低于它视为列车已停稳。
     * 2026-09-28 重标：停站期 vib p50≈0.074（开门人群噪声 p90≈0.13），巡航平滑段 p5≈0.071，
     * 0.085 是两分布目前能找到的最优切分；配合 [stillTolSec] 容差使用。
     */
    val vibStopTh: Float = 0.085f,

    // ---------- 制动判据 ----------
    /**
     * 制动判据：H 高于它视为正在制动 / 加减速。
     * 2026-09-28 实测：真实地铁制动因 0.4 Hz 低通 + 口袋朝向投影损耗，12 站到站窗内 h>0.4
     * 持续最长仅 1.3 s，全程未触发过 —— 该路径在真实线路上不可达，保留为手摇回归资产兼容，
     * 到站判定以 vib 静稳 + 乘车证据带为主（制动不再必要条件）。
     */
    val brakeAccelTh: Float = 0.40f,
    /** 制动的最短持续时间，低于它不认定为制动（过滤抖动） */
    val brakeMinSec: Double = 3.0,
    /** 制动的最长持续时间，超过它认为是曲线 / 缓行，放弃该次制动 */
    val brakeMaxSec: Double = 30.0,
    /** 制动释放：H 回落到阈值以下并持续这么久，视为该次制动结束 */
    val brakeReleaseSec: Double = 2.0,

    // ---------- 停稳 / 起步 ----------
    /** 停稳确认时长：静稳跨度达到它才算「到站」（v2 起为「跨度」而非「累计」，见 [stillTolSec]） */
    val stillConfirmSec: Double = 8.0,
    /**
     * 静稳容差：静稳跨度内允许 vib 短暂 ≥vibStopTh 的毛刺时长；超过则静稳中断、重新累计。
     * 2026-09-28 实测标定：巡航平滑段 vib<0.085 连续可达 8.6 s（纯累计规则会在区间误报到站），
     * 而真实停站的连续静稳可达 12~23 s；2 s 容差把「跨度」语义与累计区分开。
     */
    val stillTolSec: Double = 2.0,
    /** 起步确认时长：vib 持续高于「在动」阈值达它才算离站 */
    val departConfirmSec: Double = 10.0,
    /**
     * 到站后至少经过此时长才允许判 DEPART（门开人群噪声会产生 vib>runTh 的 5~10 s 连续段，
     * 无此守卫会在停站中途误判起步）。
     */
    val minDwellBeforeDepartSec: Double = 15.0,
    /**
     * 两站最短间隔：短于它记 STOP_SUSPECT，不计数。
     * 2026-09-28 试标定 60 → 68 以拦晚通勤区间信号停车（t+1388.5 与上一真站仅隔 61.5 s）——
     * 实测否决：早通勤真实站间存在 62 s（t+933.7→995.9，v2 现场用户手动 12 站确认），
     * 68 会拦真站破坏 12/12 基线。与需求 1.3 一致：区间信号停车是 v2 遗留的**不可分**
     * 问题（纯 IMU 形态与站台停稳相同），留给后续 Wi-Fi/基站指纹路线（
     * cellular-wifi-fingerprint-validate）解决；v3 只保证「真站不被拦」。
     */
    val minStopIntervalSec: Double = 60.0,

    // ---------- 乘车证据带（2026-09-28 新增，误报根因分析的「滑动窗 + 单段持续」落地）----------
    /**
     * 乘车证据下限：vib 处于 [rideBandLo, rideBandHi] 视为「列车行驶形态」样本。
     * 下限 = 巡航 vib p25（0.10），高于停站静稳区；
     */
    val rideBandLo: Float = 0.10f,
    /** 乘车证据上限：高于走路振动下限（口袋走路 vib p50≈0.6），把「走路」排除出乘车证据 */
    val rideBandHi: Float = 0.35f,
    /** 乘车证据的最短单段持续：连续（带 [stillTolSec] 容差）处于带内 ≥ 此值才算一段乘车证据 */
    val rideBandMinSec: Double = 30.0,
    /** 乘车证据的回看窗：到站判定时，最近这么长时间内存在 ≥[rideBandMinSec] 的证据段即可 */
    val rideBandWindowSec: Double = 150.0,
    /**
     * 乘车证据门开关：到站判定（含制动路径与静稳路径）统一要求近期乘车证据。
     * 拦截「站台静立等车被当成到站」「走路后站住 8 s 被当成到站」两类结构性误报
     * （2026-09-27 扔垃圾事故 + 2026-09-28 早高峰两段实测验证）。
     * 手摇回归资产（合成「列车」vib≈1.5）不经过带内，测试中以 false 注入（分层见交接文档）。
     */
    val evidenceGateEnabled: Boolean = true,

    // ---------- 滤波参数 ----------
    /** H 特征（纵向加减速）的低通截止频率 */
    val slowLpfHz: Double = 0.4,
    /** vib 带通下限 */
    val vibLowHz: Double = 3.0,
    /** vib 带通上限 */
    val vibHighHz: Double = 20.0,
    /** vib 的 RMS 统计窗长 */
    val vibWindowSec: Double = 1.0,
    /** 重力方向估计的低通截止频率（用于把去重力向量投影到水平面） */
    val gravityLpfHz: Double = 0.05,
    /** 二阶 IIR 的 Q 值（RBJ cookbook 默认） */
    val iirQ: Double = 0.7071,

    // ---------- 兜底 ----------
    /** 开始后的预热时长：期间不判定 */
    val startGraceSec: Double = 20.0,
    /**
     * 预热期内，振动持续这么久即判定「监测开始时列车已在行驶」。
     * 用于区分两种开始姿势（决定首站是否忽略）：
     * - 在上车站台开始（开始即静止）→ 第一次停站是上车站本身，应忽略；
     * - 已在车上中途开始（开始即在动）→ 第一次停站就是真实的第 1 站，必须计数。
     * 2026-09-28 由 1.0 → 3.0：站台开始时收尾走路的残余振动毛刺约 1~2 s，1 s 门槛会误判姿势
     * （该误判的计数后果已由乘车证据门兜住，但姿势标记供离线分析仍应准确）。
     */
    val startMovingConfirmSec: Double = 3.0,
    /**
     * 预热静稳否决（v3 终案，2026-09-28 早/晚通勤实测裁决）：
     * 预热内出现连续这么久的静稳（vib < vibStopTh）即证明「人还没上车、在站台」，一票否决
     * [startMovingConfirmSec] 凑满产生的「车上中途开始」判定。依据：站台开始的预热 0-20s
     * 大量静稳秒（早通勤 p50=0.077），中途上车的预热 0-20s 全在动（晚通勤全 >0.085）——
     * 站台进站前的人群/结构振动可凑满 3 s rideLike，把站台开始误判成中途开始（早通勤实测），
     * 静稳是「没上车」的铁证；而 vib 冲高判据在晚通勤起步只有 0.386~0.425、不可用（实测）。
     */
    val warmupStillVetoSec: Double = 5.0,
    /**
     * 巡航中「高于停稳阈值的振动」持续达此时长，直接置 hasRun（「确已在乘车」）。
     * 2026-09-28 新增：站台开始→上车→首段巡航没有「STOPPED→DEPART」过程，hasRun 只能由
     * 巡航自身证据产生，否则首个真实到站会被首站忽略规则吞掉（真实数据复现）。
     * 取值权衡（真实数据回放实测）：12 s 会把静坐时的手持 fidget（vib 高抖 27 s）误判成乘车；
     * 用证据带判据又太严（真实短区间频繁出带，首个真实站被吞）——
     * 最终取「vib > vibStopTh 连续（带 stillTolSec 容差）30 s」：fidget 27 s 被拦，
     * 真实区间 60~90 s 振动轻松达标。
     * v3 说明：曾试加「跨度内起步冲高」附加条件防站台误置位，实测否决（0.45 吞晚通勤起步、
     * 0.35 锁死早通勤坐姿起步）；站台误置 hasRun 的后果由预热静稳否决（[warmupStillVetoSec]）
     * 与通道 B 证据门兜住，保持 v2 纯跨度语义。
     */
    val cruiseRunConfirmSec: Double = 30.0,
    /** 断流恢复后的重新预热时长 */
    val warmupResumeSec: Double = 5.0,
    /** 最长监测时长（分钟），超过自动结束 */
    val maxMonitorMin: Double = 90.0,
    /** 到目的站后的自动结束延时 */
    val endGraceSec: Double = 30.0,
    /** 采样断流阈值：相邻样本间隔超过它视为数据缺口 */
    val dataGapSec: Double = 5.0,

    // ---------- 步态门（v3，multi-signal-detector-v3）----------
    /**
     * 步行陀螺阈值：1 s RMS 高于它视为「人在走」。
     * 2026-09-28 早/晚通勤 lab 标定：走路/楼梯 1.18+，站台静立 0.20，站姿乘车 0.11~0.14，
     * 坐姿玩手机 0.19 —— 0.6 取走路与非走路场景中点。
     */
    val walkGyroTh: Float = 0.6f,
    /** 陀螺 RMS 统计窗长（秒） */
    val gyroWindowSec: Double = 1.0,
    /** 连续步行确认时长（秒）：达到才置 isWalking（防单帧抖动） */
    val gaitConfirmSec: Double = 1.5,
    /** 步行退出容差（秒）：低于阈值持续这么久才退出 isWalking（防走路步间间歇误退） */
    val gaitReleaseSec: Double = 2.0,

    // ---------- 乘车证据通道 B：站姿乘车（v3，占比 + 列车动态组合判据）----------
    /**
     * 通道 B 主门槛：回看窗内「vib 带内」占比（%）。
     * 离线标定（2026-09-28 早/晚通勤）：150 s 窗带内占比——站姿乘车停站时刻 39~73%，
     * 站台静立 38~47%——48 是当前切分点（早站台峰值 47%，晚乘车 10/12 真值 ≥48%）。
     * 均值/中位数与 gyro 在「站台静立 vs 站姿乘车」间同分布不可分（0.115/0.106、0.083/0.079）。
     */
    val rideStandBandPct: Double = 48.0,
    /**
     * 通道 B 次门槛：占比 ≥[rideStandBandPctLo] 且窗内「列车动态冲高」（H >[hSurgeTh] 持续
     * ≥[hSurgeMinSec]）次数 ≥[rideStandSurgeCount] —— 救回占比临界（45~48%）但列车动态明确
     * 的停站（晚通勤 t+513.6：47% + H 6 次）。
     * 2026-09-28 晚通勤回放标定 40 → 45：H 冲高（加减速/过岔）在**所有**停顿段（含区间信号
     * 停车与真站）都存在，单靠冲高数区分不了真假停站；占比下限低于 45 会把信号停车放行
     * （t+440.1：41%+5 次、t+827.1：40%+6 次 = 现场真值 n=10 之前多计的 2 站，回放错位
     * 病灶）。45 以下一律拦——真实站姿停站实测占比均 ≥47%。
     */
    val rideStandBandPctLo: Double = 45.0,
    val rideStandSurgeCount: Int = 5,
    /** 通道 B 回看窗（秒）：占比与冲高计数共用窗口 */
    val rideStandWindowSec: Double = 150.0,
    /** 列车动态冲高的 H 阈值（0.4 Hz 低通后的纵向加加速度幅值） */
    val hSurgeTh: Float = 0.10f,
    /** 列车动态冲高的最短持续（秒） */
    val hSurgeMinSec: Double = 2.0,

    // ---------- v4 蜂窝分区（cell-zone-detector-v4：蜂窝回答「在哪」，IMU 回答「停没停」）----------
    /**
     * 站区门开关：到站候选（检测器原始 STATION_ARRIVED / 分区侦察补检）必须落在包含
     * 「期望下一站」的站区内才计数，否则记 ZONE_SUPPRESSED——区间信号停车 / 上车站内停顿免疫。
     * **2026-09-29 深夜起默认 true（用户拍板提前切主通道，节前最后一天实测）**；
     * 影子模式改为显式 copy(false)（见 RealCommuteMorningRegressionTest 影子用例）。
     */
    val cellZoneGateEnabled: Boolean = true,
    /**
     * 站区侦察开关：已确认站区内用**放宽的静稳判据**补检 v3 静默漏检的站
     * （晨高峰停站窗 vib 均值 0.11~0.19，高于全局 vibStopTh=0.085 → 8 s 静稳跨度形不成 → 漏检）。
     * 误报风险由站区身份兜底（站区只在车站附近出现）。**2026-09-29 深夜起默认 true（同上）**。
     */
    val cellZoneScoutEnabled: Boolean = true,
    /** 进区 / 退区驻留确认拍数（cellid 流 ≈1 Hz；实测小区切换到停稳提前 0~58 s） */
    val cellZoneConfirmSamples: Int = 2,
    /**
     * 站区门活性窗口：最近一次 ZONE 转移（进区/退区）在此窗口内 gate 才生效；超时视为
     * 小区流缺席/断流/映射失配 → 判定**自动回退 v3**（防「gate 开 + 小区流死 → 全程 0 计数
     * 0 提醒」，2026-09-29 晚通勤回归暴露的 P0 缺口）。正常站间 100~201 s 必有区转移刷新
     * 活性，300 s 留足余量且断流后最多 5 min 恢复 v3 兜底。
     */
    val cellZoneGateLivenessSec: Double = 300.0,
    /**
     * 区内放宽静稳阈值：晨高峰停站窗 vib 均值 0.106~0.194（2026-09-29 早通勤 12 站实测），
     * 0.22 覆盖全部停站均值且仍高于站台静立（p50 0.092）与站姿乘车停站的重叠区下限；
     * 误触发由「站区身份 + 期望站位校验」双门兜底。
     */
    val zoneStillVibTh: Float = 0.22f,
    /** 区内静稳容差（比全局 stillTolSec 稍宽：晨高峰人群噪声毛刺更密） */
    val zoneStillTolSec: Double = 3.0,
    /** 区内静稳确认时长（比全局 stillConfirmSec 短：站区先验已提供强身份证据） */
    val zoneStillConfirmSec: Double = 6.0,
    /**
     * 目的站兜底到站：进入目的站区后经过此时长仍未静稳计数（用户走动噪声极大，
     * 如玉双路停站窗 vib 均值 0.66）→ 直接产生到站候选。目的站是提醒的最终目标，
     * 宁可提前不可漏发；提前量由 zone-entry 早于停稳 0~58 s 决定。
     */
    val zoneArrivalFallbackSec: Double = 50.0,

    // ---------- CSV 保留策略（非算法阈值，同样集中在此避免第二处硬编码）----------
    /** 保留最近多少次会话（三件套同删） */
    val retentionSessions: Int = 10,
    /** 保留最近多少天内的会话 */
    val retentionDays: Int = 14,
    /** logs 目录体积上限（MB），超出则从最旧会话删起 */
    val retentionMaxMb: Int = 200,
) {
    companion object {
        /** 出厂默认参数（mutable 版本供调参面板使用） */
        val Default: TuningConfig = TuningConfig()
    }
}
