package com.metrostop.reminder.core.lab

/**
 * 实验室采集的场景清单（纯 Kotlin，JVM 可测）——**同时也是用户的采集操作清单**。
 *
 * 语义：每条标记 = 「**进入**该场景」；两次标记之间归上一个场景（单次点按即可，无需起止配对）。
 * 阈值（采样率等采集参数）不在此处 —— 本文件只是**清单 + 事件语义**，不含可调算法阈值，
 * 因此不进 `TuningConfig`（红线期间该文件零改动）。
 */
object LabScenarios {

    /** 一个可标记场景 */
    data class Scenario(
        /** 事件 detail 里落盘的稳定 id（如 `enter_walk_indoor`），分析脚本按它切片 */
        val id: String,
        /** UI 显示名（中文短句） */
        val name: String,
        /** UI 分组 */
        val group: Group,
    )

    enum class Group(val label: String) {
        GROUND("地面（有 GPS，金标准段）"),
        HALL("站厅 / 通道（地下无 GPS）"),
        PLATFORM("站台"),
        TRAIN("乘车（核心正样本）"),
        OTHER("其他"),
    }

    val ALL: List<Scenario> = listOf(
        // ---- 地面（有 GPS：getSpeed>0 = 在走 的地面真值，有监督标定段）----
        Scenario("enter_walk_indoor", "平地走路（室内/楼下）", Group.GROUND),
        Scenario("enter_walk_outdoor", "平地走路（户外）", Group.GROUND),
        Scenario("enter_run", "奔跑 / 小跑", Group.GROUND),
        Scenario("enter_stairs", "上下楼梯", Group.GROUND),
        Scenario("enter_stand_ground", "站定不动（地面）", Group.GROUND),
        // ---- 站厅 / 通道（地下，定位开始衰减的对照段）----
        Scenario("enter_hall_walk", "站厅/换乘通道走路", Group.HALL),
        Scenario("enter_escalator", "扶梯（站定乘梯）", Group.HALL),
        Scenario("enter_stairs_metro", "地铁楼梯/台阶", Group.HALL),
        // ---- 站台 ----
        Scenario("enter_platform_wait", "站台静立等车", Group.PLATFORM),
        Scenario("enter_platform_walk", "站台走动", Group.PLATFORM),
        Scenario("enter_train_door", "上车（车门处走动）", Group.PLATFORM),
        // ---- 乘车（核心正样本：真实车厢振动基线）----
        Scenario("enter_ride_sit_phone", "乘车·坐着玩手机", Group.TRAIN),
        Scenario("enter_ride_sit_still", "乘车·坐着扶稳/静坐", Group.TRAIN),
        Scenario("enter_ride_stand", "乘车·站姿握扶手", Group.TRAIN),
        Scenario("enter_ride_walk", "车厢内走动", Group.TRAIN),
        Scenario("enter_train_stop", "列车进站停稳（在车上）", Group.TRAIN),
        // ---- 其他 ----
        Scenario("enter_transfer", "换乘过程（下车→通道→候车）", Group.OTHER),
        Scenario("enter_exit", "出站步行", Group.OTHER),
    )

    /** 通知兜底按钮（无场景名）与旧版数据的落盘 detail */
    const val GENERIC_MARK = "generic"

    val byId: Map<String, Scenario> = ALL.associateBy { it.id }
}
