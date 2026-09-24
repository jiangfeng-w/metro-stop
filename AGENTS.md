# Agents 规则

「到站了」= 一个**个人自用**的 Android 应用：坐地铁怕坐过站时，选好线路与目的站一键开始，App 通过**加速度传感器识别列车进站（减速-停稳）并计数**，在目的站前一站与到站时各发一条通知 + 震动提醒。**不用定位**（隧道内无 GPS），不上架、不做小米超级岛。

**开发文档全在 `docs/`。开始时先读 `docs/README.md`（索引 + 自举环境），再读总纲 `docs/spec/001-需求与方案.md`。**

## 硬性规则（必守，违反即视为返工）

1. `core/` 包为**纯 Kotlin**：禁止 `import android.*`，禁止直接调用 `System.currentTimeMillis()` / `Log` / `Context`（时间与日志用注入的函数）。这是「CSV 回放 = 现场行为」的前提。
2. **全部阈值参数只能定义在 `core/model/TuningConfig.kt`**，禁止在其它文件硬编码阈值；每次调参必须走闭环：导出实测 CSV → 回放验证 → 新参数写回 → **留下一个 replay 回归用例**。
3. **不引入任何定位 / 地图 / 第三方位置 SDK**，不申请定位权限。传感器仅用 `TYPE_LINEAR_ACCELERATION`（无则降级 `TYPE_ACCELEROMETER`），采样 50 Hz。
4. 通知渠道（`monitor_ongoing` / `station_alert` / `station_alert_silent`）的属性**创建后系统不可更改**：M2 一次定稿，之后只允许改文案，不允许改重要性 / 震动模式（要改只能卸载重装）。
5. 前台服务类型 `specialUse`（含 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性）；`onStartCommand` 首行必须 `startForeground`（5 秒红线）；从后台启动必须 catch `ForegroundServiceStartNotAllowedException` 并给出可见反馈，禁止静默失败。
6. PendingIntent 一律 `FLAG_IMMUTABLE` + 显式 Intent + **唯一 requestCode**；通知按钮接收器用动态注册（`RECEIVER_NOT_EXPORTED`）。
7. 状态来源单一：服务写 `SessionHolder`，UI / 磁贴 / 小组件只读；磁贴 / 小组件 / 接收器内**不得在主线程读 DataStore**，只发 ACTION 给服务。
8. 应用名固定「到站了」；包名 `com.metrostop.reminder`（首次装机前可改，之后不可）。

## 技术栈

- Kotlin 2.2.x + Jetpack Compose（单 Activity 单屏）；minSdk 26 / compileSdk 36 / targetSdk 36。
- Gradle Wrapper 8.13 + AGP 8.13（版本唯一来源 `gradle/libs.versions.toml`；升级/降级的应急处理见 `docs/development.md`「版本组合与应急旋钮」）。
- 无任何第三方 SDK（不用高德/百度/地图/定位/推送）。
- 目标设备：红米 K80（HyperOS 4 beta / Android 17），adb 侧载。

## 提交信息规范

- 中文 Conventional Commits：`feat:` / `fix:` / `docs:` / `chore:` / `refactor:`，单行概括；
- 需要时加 body：空一行后逐条 `- ` 写「改了什么 / 为什么」。

## 当前进度与交接（2026-09-25）

- **已完成 S0**：目录结构 + AI 文档体系 + Gradle 骨架（仅配置，无 Kotlin 代码）。首个 commit：`chore: 初始化 metro-stop 项目骨架与文档体系`。
- **下一步 S1（装环境）**：JDK 17 + Android cmdline-tools + platform-tools + Gradle 8.13（免安装），步骤见 `docs/README.md`「自举环境」；需用户配合：手机开 USB 调试并插线。验收：`java -version` 显示 17.x、`adb devices` 列出设备。
- **再下一步 S2（M1）**：按 `docs/spec/active/mvp-stop-counter/需求.md` 写代码。
- **接手阅读顺序**：本文件 → `docs/README.md`（需求速览 + 自举环境 + 下一步）→ `docs/spec/active/mvp-stop-counter/需求.md`（当前需求）→ 需要背景时读 `docs/spec/需求与方案.md`。
- **分工**：AI 负责写代码与在本机代敲命令（会话内环境变量可能不生效，用绝对路径）；用户负责手机端操作、通勤实测与验收反馈。

## 入口

- 文档索引 + 需求速览表 + 自举环境：`docs/README.md`
- 跨期总纲（背景 / 范围 / 架构 / 状态机规格 / 里程碑 / 后续优化）：`docs/spec/需求与方案.md`
- spec 目录约定与状态规范：`docs/spec/README.md`
- 开发中的需求：`docs/spec/active/<需求>/`；已完成：`docs/spec/done/`；挂起：`docs/spec/suspended/`
- 构建 / 调试 / 踩坑：`docs/development.md`
