package com.metrostop.reminder

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位卫生自检（2026-09-30 批次 A2，cell-zone-detector-v4 复核意见 §2.1）。
 *
 * 背景：`ACCESS_FINE_LOCATION` 是 v4 主链路读 CellInfo 的**平台校验门槛**（Android 10+ 强制，
 * AGENTS.md 规则 3 已定稿该权限「仅用于读取小区标识」）。本测试把纪律锁成可回归断言：
 * **坐标 API（`android.location.*` / getLatitude / getLongitude / FusedLocation）只允许出现在
 * lab loc 流的白名单文件中**——防止后来者把「权限升格」误解为「可以定位」。
 */
class LocationHygieneTest {

    /** 白名单：允许出现坐标 API 的文件（相对 `src/main/java/`） */
    private val whitelist = setOf(
        // lab loc 流：GNSS/网络定位采集（旁路工具，lab 下线时随权限一并移除）
        "platform/lab/LabCollectorService.kt",
    )

    private val forbidden = listOf(
        "android.location.", // 坐标 API 包根（LocationManager / GnssStatus / Location…）
        "getLatitude",       // CellIdentity.getLatitude() / Location.getLatitude()
        "getLongitude",
        "FusedLocation",
    )

    private fun sources(): List<File> {
        val root = File("src/main/java")
        assertTrue("找不到源码目录：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun relPath(f: File): String =
        f.invariantSeparatorsPath
            .substringAfter("src/main/java/")
            .substringAfter("com/metrostop/reminder/")

    @Test
    fun `坐标API只允许出现在lab loc流白名单文件`() {
        val offenders = sources().mapNotNull { f ->
            val rel = relPath(f)
            if (forbidden.any { f.readText().contains(it) } && rel !in whitelist) rel else null
        }
        assertTrue(
            "发现白名单外的定位/坐标 API 引用（AGENTS.md 规则 3：只读小区标识不读坐标）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `白名单文件必须存在_防白名单过期漂移`() {
        val srcs = sources()
        whitelist.forEach { w ->
            assertTrue(
                "白名单文件已不存在，请同步更新 LocationHygieneTest：$w",
                srcs.any { relPath(it) == w },
            )
        }
    }
}
