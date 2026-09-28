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
    /** 两站最短间隔：短于它记 STOP_SUSPECT，不计数 */
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
     * 巡航中「高于停稳阈值的振动」持续达此时长，直接置 hasRun（「确已在乘车」）。
     * 2026-09-28 新增：站台开始→上车→首段巡航没有「STOPPED→DEPART」过程，hasRun 只能由
     * 巡航自身证据产生，否则首个真实到站会被首站忽略规则吞掉（真实数据复现）。
     * 取值权衡（真实数据回放实测）：12 s 会把静坐时的手持 fidget（vib 高抖 27 s）误判成乘车；
     * 用证据带判据又太严（真实短区间频繁出带，首个真实站被吞）——
     * 最终取「vib > vibStopTh 连续（带 stillTolSec 容差）30 s」：fidget 27 s 被拦，
     * 真实区间 60~90 s 振动轻松达标。
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
