# Agents 规则

「到站了」= 一个**个人自用**的 Android 应用：坐地铁怕坐过站时，选好线路与目的站一键开始，App 通过**加速度传感器识别列车进站（减速-停稳）并计数**，在目的站前一站与到站时各发一条通知 + 震动提醒。**不用定位**（隧道内无 GPS），不上架、不做小米超级岛。

> **本文件只写「规则与指针」，不写进度。** 当前进度 / 交接 / 红线 / 通勤实测清单 / 本机设备与环境状态**一律在 [`docs/进度与交接.md`](docs/进度与交接.md)**（新会话必读）。
> **开发文档全在 `docs/`**，入口 [`docs/README.md`](docs/README.md)（索引 + 需求速览 + 自举环境）；跨期总纲 [`docs/spec/需求与方案.md`](docs/spec/需求与方案.md)。

## 硬性规则（必守，违反即视为返工）

1. `core/` 包为**纯 Kotlin**：禁止 `import android.*`，禁止直接调用 `System.currentTimeMillis()` / `Log` / `Context`（时间与日志用注入的函数）。这是「CSV 回放 = 现场行为」的前提。
2. **全部阈值参数只能定义在 `core/model/TuningConfig.kt`**，禁止在其它文件硬编码阈值；每次调参必须走闭环：导出实测 CSV → 回放验证 → 新参数写回 → **留下一个 replay 回归用例**。
3. **不引入任何定位 / 地图 / 第三方位置 SDK**，不申请定位权限。传感器仅用 `TYPE_LINEAR_ACCELERATION`（无则降级 `TYPE_ACCELEROMETER`），采样 50 Hz。
4. 通知渠道（`monitor_ongoing` / `station_alert` / `station_alert_silent`）的属性**创建后系统不可更改**：M2 一次定稿，之后只允许改文案，不允许改重要性 / 震动模式（要改只能卸载重装）。
5. 前台服务类型 `specialUse`（含 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性）；`onStartCommand` 首行必须 `startForeground`（5 秒红线）；从后台启动必须 catch `ForegroundServiceStartNotAllowedException` 并给出可见反馈，禁止静默失败。
6. PendingIntent 一律 `FLAG_IMMUTABLE` + 显式 Intent + **唯一 requestCode**；通知按钮接收器用动态注册（`RECEIVER_NOT_EXPORTED`）。
7. 状态来源单一：服务写 `SessionHolder`，UI / 磁贴 / 小组件只读；磁贴 / 小组件 / 接收器内**不得在主线程读 DataStore**，只发 ACTION 给服务。
8. 应用名固定「到站了」；包名 `com.metrostop.reminder`（首次装机前可改，之后不可）。
9. **每个阶段 / 里程碑完成后，必须同步更新交接与清单**（不靠人记）：
   - [`docs/进度与交接.md`](docs/进度与交接.md)：已完成什么、下一步是什么、需要用户配合什么；
   - `docs/README.md` 的「需求速览」表与「下一步」；
   - 需求状态变化时按 `docs/spec/README.md` 第五节做「三处联动」（目录 / frontmatter / 速览表）。
   未更新交接即视为该阶段未完成。

> 🚫 **红线（临时，通勤实测数据回传前有效）**：禁止改算法路径 / 卸载或清数据 / 改系统级配置，违反即实测作废。四条完整清单见 [`docs/进度与交接.md`](docs/进度与交接.md)「红线」。

## 技术栈

- Kotlin 2.2.x + Jetpack Compose（单 Activity 单屏）；minSdk 26 / compileSdk 36 / targetSdk 36。
- Gradle Wrapper 8.13 + AGP 8.13（版本唯一来源 `gradle/libs.versions.toml`；升级/降级的应急处理见 `docs/development.md`「版本组合与应急旋钮」）。
- 无任何第三方 SDK（不用高德/百度/地图/定位/推送）。
- 目标设备：红米 K80（HyperOS 4 beta / Android 17），adb 侧载。

## 提交信息规范

- 中文 Conventional Commits：`feat:` / `fix:` / `docs:` / `chore:` / `refactor:`，单行概括；
- 需要时加 body：空一行后逐条 `- ` 写「改了什么 / 为什么」；
- **协作规则：commit 前必须先征求用户同意，用户说「提交」才执行。**

## 分工

- AI 负责写代码与在本机代敲命令（会话内环境变量可能不生效，用绝对路径）；用户负责手机端操作、通勤实测与验收反馈。
- **用户使用手机期间，AI 不做 force-stop / 注入点按等扰动操作。**

## 接手顺序与入口

1. 本文件（规则）；
2. [`docs/README.md`](docs/README.md)：文档索引 + **需求速览表（唯一清单）** + 自举环境；
3. [`docs/进度与交接.md`](docs/进度与交接.md)：**当前进度 / 下一步 / 需要用户配合的事 / 红线与通勤清单 / 本机设备与环境状态**；
4. 当前需求文档 `docs/spec/active/<需求>/需求.md`（当前开发中的需求见 `docs/README.md` 速览表）；需要背景时读 [`docs/spec/需求与方案.md`](docs/spec/需求与方案.md)。

- spec 目录约定与状态规范：`docs/spec/README.md`
- 开发中的需求：`docs/spec/active/<需求>/`；已完成：`docs/spec/done/`；挂起：`docs/spec/suspended/`
- 构建 / 调试 / 踩坑 / 环境安装：`docs/development.md`
