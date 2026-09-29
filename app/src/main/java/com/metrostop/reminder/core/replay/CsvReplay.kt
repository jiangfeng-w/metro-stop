package com.metrostop.reminder.core.replay

import com.metrostop.reminder.core.cell.CellZoneLine
import com.metrostop.reminder.core.feature.FeatureExtractor
import com.metrostop.reminder.core.fsm.MonitorSession
import com.metrostop.reminder.core.model.MotionSample
import com.metrostop.reminder.core.model.RouteSpec
import com.metrostop.reminder.core.model.TuningConfig

/**
 * CSV 回放器（纯 Kotlin，无 android.*）：读 sensor CSV → 特征提取 → 会话 → 事件序列。
 *
 * 与现场走**完全相同**的 FeatureExtractor + MonitorSession 代码路径与时间基准（样本 tMs），
 * 因此「CSV 回放 = 现场行为」；也是 TuningConfig 调参闭环的验证手段。
 *
 * CSV 表头（总纲 7.2）：`t_ms,utc_ms,ax,ay,az,lx,ly,lz,gx,gy,gz,vib,h,state,n`
 * 回放只用前 11 列（vib/h/state/n 由回放自行重算，避免用现场结果「自证」）。
 *
 * v4（cell-zone-detector-v4）：可选小区流（`t_ms,utc_ms,cells`，与 lab cellid 同格式）与
 * 站区映射，按 tMs 与传感器样本合并后喂给会话——v4 融合判定的回放与现场同路径。
 */
object CsvReplay {

    /** sensor CSV 的原始一行（vib/h/state/n 列忽略） */
    private data class Row(
        val tMs: Long,
        val utcMs: Long,
        val ax: Float,
        val ay: Float,
        val az: Float,
        val lx: Float?,
        val ly: Float?,
        val lz: Float?,
        val gx: Float?,
        val gy: Float?,
        val gz: Float?,
    )

    /** 一条小区样本（已归一为 `pci:ci` 身份；主服务排首） */
    data class CellSample(
        val tMs: Long,
        val utcMs: Long,
        val main: String,
        val neighbors: List<String>,
    )

    /** 回放一行 sensor CSV 文本（含可选表头） */
    fun replay(csv: String, route: RouteSpec, config: TuningConfig = TuningConfig.Default): ReplayResult {
        return replay(parse(csv), route, config)
    }

    /** v4 融合回放：sensor CSV + 可选小区 CSV + 站区映射 */
    fun replay(
        csv: String,
        route: RouteSpec,
        config: TuningConfig = TuningConfig.Default,
        cellCsv: String?,
        zoneLine: CellZoneLine?,
    ): ReplayResult {
        return replay(parse(csv), route, config, if (cellCsv != null) parseCellCsv(cellCsv) else emptyList(), zoneLine)
    }

    fun replay(
        rows: List<MotionSample>,
        route: RouteSpec,
        config: TuningConfig = TuningConfig.Default,
        cellRows: List<CellSample> = emptyList(),
        zoneLine: CellZoneLine? = null,
    ): ReplayResult {
        val extractor = FeatureExtractor(config)
        val session = MonitorSession(route, config, zoneLine)
        val events = ArrayList<com.metrostop.reminder.core.model.DetectorEvent>(64)

        val first = rows.firstOrNull() ?: return ReplayResult(emptyList(), 0, 0, null)
        events += session.start(first.tMs).events
        // 小区流按 tMs 与传感器样本交错（与现场监听回调的时序粒度一致）
        var cellIdx = 0
        for (s in rows) {
            while (cellIdx < cellRows.size && cellRows[cellIdx].tMs <= s.tMs) {
                val c = cellRows[cellIdx++]
                events += session.onCellSample(c.tMs, c.main, c.neighbors)
            }
            events += session.tick(extractor.process(s)).events
        }
        // 回放结束：若还没结束，用最后一个样本时间收尾（便于与现场「手动结束」对齐时单独标注）
        if (session.running) {
            val lastMs = rows.last().tMs
            events += session.finish(lastMs, com.metrostop.reminder.core.model.EndReason.MANUAL).events
        }
        return ReplayResult(
            events = events,
            finalStationCount = session.stationCount,
            samples = rows.size,
            sensorCsvName = null,
        )
    }

    /** 解析 CSV 文本为样本列表（跳过表头与空行；非法行忽略并跳过） */
    fun parse(csv: String): List<MotionSample> {
        val out = ArrayList<MotionSample>(4096)
        for (line in csv.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("t_ms")) continue // 表头
            val p = s.split(',')
            if (p.size < 4) continue
            val tMs = p[0].trim().toLongOrNull() ?: continue
            val utcMs = p.getOrNull(1)?.trim()?.toLongOrNull() ?: tMs
            val ax = p.getOrNull(2)?.trim()?.toFloatOrNull() ?: continue
            val ay = p.getOrNull(3)?.trim()?.toFloatOrNull() ?: continue
            val az = p.getOrNull(4)?.trim()?.toFloatOrNull() ?: continue
            out += MotionSample(
                tMs = tMs,
                utcMs = utcMs,
                ax = ax,
                ay = ay,
                az = az,
                lx = p.getOrNull(5)?.trim()?.toFloatOrNull(),
                ly = p.getOrNull(6)?.trim()?.toFloatOrNull(),
                lz = p.getOrNull(7)?.trim()?.toFloatOrNull(),
                gx = p.getOrNull(8)?.trim()?.toFloatOrNull(),
                gy = p.getOrNull(9)?.trim()?.toFloatOrNull(),
                gz = p.getOrNull(10)?.trim()?.toFloatOrNull(),
            )
        }
        return out
    }

    /**
     * 解析小区流 CSV（`t_ms,utc_ms,cells`，cells = `pci:ci:rssi` 多值 `|` 分隔、主服务排首）。
     * 身份只取 `pci:ci`（剔除 rssi——信号强度波动不是小区切换）。
     */
    fun parseCellCsv(csv: String): List<CellSample> {
        val out = ArrayList<CellSample>(1024)
        for (line in csv.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("t_ms")) continue
            val p = s.split(',')
            if (p.size < 3) continue
            val tMs = p[0].trim().toLongOrNull() ?: continue
            val utcMs = p.getOrNull(1)?.trim()?.toLongOrNull() ?: tMs
            val tokens = p[2].split('|').filter { it.isNotBlank() }
            val ids = tokens.map { it.split(':').take(2).joinToString(":") }
            out += CellSample(
                tMs = tMs,
                utcMs = utcMs,
                main = ids.firstOrNull() ?: "",
                neighbors = ids.drop(1),
            )
        }
        return out
    }
}
