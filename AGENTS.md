# Agents 规则

「到站了」= 一个**个人自用**的 Android 应用：坐地铁怕坐过站时，选好线路与目的站一键开始，App 通过**加速度传感器识别列车进站（减速-停稳）并计数**，在目的站前一站与到站时各发一条通知 + 震动提醒。**不用定位**（隧道内无 GPS），不上架、不做小米超级岛。

**开发文档全在 `docs/`。开始时先读 `docs/README.md`（索引 + 需求速览 + 自举环境），再读跨期总纲 `docs/spec/需求与方案.md`。**

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
   - 本文档「当前进度与交接」一节：已完成什么、下一步是什么、需要用户配合什么；
   - `docs/README.md` 的「需求速览」表与「下一步」；
   - 需求状态变化时按 `docs/spec/README.md` 第五节做「三处联动」（目录 / frontmatter / 速览表）。
   未更新交接即视为该阶段未完成。

## 技术栈

- Kotlin 2.2.x + Jetpack Compose（单 Activity 单屏）；minSdk 26 / compileSdk 36 / targetSdk 36。
- Gradle Wrapper 8.13 + AGP 8.13（版本唯一来源 `gradle/libs.versions.toml`；升级/降级的应急处理见 `docs/development.md`「版本组合与应急旋钮」）。
- 无任何第三方 SDK（不用高德/百度/地图/定位/推送）。
- 目标设备：红米 K80（HyperOS 4 beta / Android 17），adb 侧载。

## 提交信息规范

- 中文 Conventional Commits：`feat:` / `fix:` / `docs:` / `chore:` / `refactor:`，单行概括；
- 需要时加 body：空一行后逐条 `- ` 写「改了什么 / 为什么」。

## 当前进度与交接（2026-09-25）

- **已完成 S0**：目录结构 + AI 文档体系 + Gradle 骨架（仅配置，无 Kotlin 代码）。
- **已完成 S1**：环境（JDK 17 / SDK / Gradle 8.13 免安装 / 无线调试 / 装机）+ 首次编译通过。
- **已完成 S2（M1）代码与构建**，实际落点：
  - **core/（纯 Kotlin，禁 android.*）**：`TuningConfig`（阈值唯一来源）、`MotionSample`/`Features`/`DetectorState`/`DetectorEvent`/`MonitorUiState`/`RouteSpec`、`RingBuffer`+`FeatureExtractor`（0.4 Hz 低通 → H；3–20 Hz 带通 RMS → vib；全因果）、`StationStopDetector`（三条件联合 + STOP_SUSPECT/BRAKE_ABORT/DATA_GAP 兜底）、`MonitorSession`（计数 / D−1 与到站提醒去重 / ±1 纠错 / 90 min 兜底 / 到站延时结束）、`CsvReplay`、`LineRepository`。
  - **platform/**：`MonitorService`（首行 `startForeground`、specialUse、PARTIAL_WAKE_LOCK、把 ACTION 收到动作转服务）、`SensorCollector`（50 Hz，LINEAR 优先降级加速度计）、`Notifications`（3 渠道一次定稿）+`Notifier`、`MonitorActionReceiver`（**动态注册 RECEIVER_NOT_EXPORTED**，未进清单）、`SettingsStore`、`CsvRecorder`（Channel 队列 + 独立 IO scope，stop 时等落盘）、`SessionHolder`、`WakeLockGuard`、`KeepAliveHelper`、`MonitorTileService`。
  - **ui/**：`AppScreen`/`RouteSelector`（目的站限选上车站之后）/`StatusCard`/`Controls`/`DebugPanel`（含离线回放）/`SettingsCard`/`KeepAliveGuide`/`AppViewModel`。
  - **测试**：`gradlew test` 23 项全绿，含 `CsvReplayTest`（合成行程回放事件序列断言 + 两次回放逐行一致）、`FeatureExtractorTest`、`DetectorStateMachineTest`、`RouteSpecTest`。
  - **构建装机**：`:app:assembleDebug` 通过（12.7 MB），`adb install -r` 成功，App 启动无 crash。
- **S2（M1）实机验收：工程与算法部分已通过**（2026-09-25，红米 K80 真机）：
  - ✅ 磁贴一键开始（**无需打开 App**，自动用上次路线）、常驻通知 3 s 内出现（`isForeground=true`）
  - ✅ 前台服务类型现场确认 `types=0x40000000` = `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
  - ✅ 通知 3 渠道属性实测符合定稿（LOW / HIGH+震动 / HIGH+静音）→ **M2 无需卸载重装**
  - ✅ 采样率 47~50 Hz；静置基线 `vib` 均值 0.0083（阈值 0.08，10 倍余量）、`H` 均值 0.0005（阈值 0.40，800 倍余量），零误检
  - ✅ **CSV 离线回放与现场完全一致**：真实 3 站行程 19 条事件中 18 条**时间戳逐毫秒一致**（仅 `WARMUP_DONE` 有 43 ms 启动相位差）
  - ✅ 完整闭环真机跑通：静置→摇动→静置（计数 1）→…→`n=k` 发到站提醒 → **30.006 s 精确自动结束** → 服务退出、通知零残留
  - ✅ `gradlew test` **29 项全绿**（含 2 个实测 CSV 回归套件）
  - ⬜ **剩余两项需真实通勤**：地铁 ≥5 站实测（误差 ≤1）、锁屏 10 分钟 50 Hz 压测
- **本轮实机验证修复了 5 个真实缺陷**（详见 `docs/spec/active/mvp-stop-counter/验收记录.md` 第五节）：
  1. 🔴 **`CRUISE` 不累计静止** → 缓刹/制动特征被滤波抹平时**完全漏检到站**（把「三条件联合」实现成了门控）。修法：`CRUISE` 中也累计 `stillForSec`，`note=still_no_brake` 标记；
  2. 🔴 **磁贴启动时服务当场 `stopSelf` 自杀**（修缺陷 5 时引入的回归：`onStartCommand` 末尾按 `isRunning` 退出，而磁贴路径异步读「上次路线」，此刻仍 false）→ CSV 全丢；
  3. 🟡 CSV 表头被 `bufferedWriter()` 截断、短会话 0 字节 → 改 append 模式 + 表头/meta 同步写；
  4. 🟡 自动结束后 1 s ticker 把常驻通知重新贴出 → 加 `isRunning` 守卫 + 主动 `cancel`；
  5. 🟢 测试提醒后服务不退出（残留误导通知）；`onDestroy` 抹掉回放结果。
- **用户实测反馈 3 问题 → 又修 4 项**（2026-09-25 晚）：
  1. **通知不震动**（根因链两段）：① 渠道震动在 HyperOS 不生效 → 改 App 侧 `VibratorHelper` 主动调用；② 主动调用仍不震，`dumpsys vibrator_manager` 显示 `ignored_for_settings | usage: UNKNOWN` —— **无 `VibrationAttributes` 的 `createWaveform` 被归为 `UNKNOWN`，而系统 `VibrationIntensities` 中 `UNKNOWN = OFF`**（`ALARM` 才是 MEDIUM）→ 改用 `vibrate(effect, VibrationAttributes(USAGE_ALARM))`。修正后日志 `effect | finished | duration: 1016ms | usage: ALARM`。
  2. **「车上中途开始」时第一个真实到站被吞**（用户反馈：「上车站→目的站只差 1 站，却要摇两次才提醒」）→ 首站忽略规则只看 `hasRun`，未区分开始姿势。新增 `TuningConfig.startMovingConfirmSec` + `StationStopDetector.startedInMotion`（预热期内振动持续 1 s 即判定「开始时已在行驶」），该姿势下首次停站直接计数；`WARMUP_DONE` note 记 `started_in_motion` / `started_at_platform`。⚠️ **该幅度判据有已知缺陷**（站台走路也会超阈值 → 多算 1 站），已立项 [`docs/spec/active/gait-discrimination`](docs/spec/active/gait-discrimination/需求.md) 用步态识别取代，届时 `startedInMotion` / `startMovingConfirmSec` 一并删除。
  3. 通知栏看不到已运行时间 → `setUsesChronometer(true)`（系统自动走动）。
  4. 磁贴状态需收起重开通知栏才刷新 → `onStartListening` 订阅 `SessionHolder`，状态翻转即 `updateTile()`。
- **回归资产（硬性规则 2）**：`app/src/test/resources/replay/` 下有 5 个实测文件（摇动/静置、3 站行程、车上开始单站行程 + 2 份现场事件金标准），对应 `RealCsvRegressionTest` / `RealJourneyRegressionTest` / `RealInMotionStartRegressionTest`，另有纯逻辑套件 `CsvReplayTest` / `FeatureExtractorTest` / `DetectorStateMachineTest` / `RouteSpecTest` / `CsvRetentionTest`。**改阈值必跑 `gradlew test`**（当前 **54 项**）。
- **代码已提交**（2026-09-25）：`091bd5c feat: S2(M1) 到站计数主链路实现（core/platform/ui + 回归资产）` + `2bc00ee docs: S2(M1) 实机验收记录与交接更新`。
- **已立项的新需求（2026-09-25 讨论后落档，见 `docs/README.md` 速览表）**：
  1. `gait-discrimination`（`ready`）—— 步态识别取代 `startedInMotion` 幅度判据，修「站台走路 → 多算 1 站」；零新增权限；**前置：用户补录走路 / 乘车 CSV 标定阈值**；
  2. `csv-storage-policy`（**`done`**）—— `record_csv` 默认关闭 + 保留最近 10 次 / 14 天 / 200 MB 自动清理（已验收，见下）；
  3. `trip-history-db`（`planned`，归 M3）—— SQLite 存行程摘要与停站明细（与 CSV 解耦），最简历史列表；**开工前需定文档第四节 5 项待决策**。
- ✅ **`csv-storage-policy` 已完成并验收**（2026-09-25，暂停解除后的第一个需求）：
  - **core（纯 Kotlin）**：`core/retention/CsvRetention.kt` —— `CsvSessionScanner`（`sensor_`/`events_`/`_meta` 分组 + 缺文件容错）+ `CsvRetention.selectForDeletion`（年龄 / 次数 / 体积三规则叠加，`activeStamp` 永不删，返回最旧在前）；`TuningConfig` 新增 `retentionSessions=10` / `retentionDays=14` / `retentionMaxMb=200`；
  - **platform**：`LogsCleaner`（IO 薄壳：`scan()` 占用 / `clean()` 按策略 / `cleanAll()` 手动清空；只碰会话三件套，`replay_report.txt` 与未知文件不动）；`MonitorService.beginMonitoring` 起 IO 协程清理（**无论是否在录都跑**，`activeStamp` 传本次会话名）；
  - **默认值与迁移**：`SettingsStore.recordCsv` fallback `true→false`、`AppViewModel._recordCsv` 初值 `false`；新增 `migrateRecordCsvIfNeeded()`（无 `record_csv` 键 + 有 `last_route_line_id` 键 → 显式写 `true`），在服务 `preloadSettings` 与 `AppViewModel.init` 各调一次。**⚠️ 此迁移是实测发现的必要补丁**：原需求文档假设「已装机设备存的是显式 `true`」，真机 dump DataStore 证明**该键根本不存在**（用户从没拨过开关），只改 fallback 会让老设备静默停录、卡住步态标定取数；
  - **UI**：`SettingsCard` 文案改「调试用，约 15 MB/小时，自动保留最近 10 次」+ 显示 `logs` 占用；`DebugPanel` 新增「立即清理日志」按钮；`AppViewModel.refreshLogsUsage()` / `cleanLogsNow()`；
  - **测试**：新增 `CsvRetentionTest` **20 项**（三件套分组 / 缺文件容错 / 超次数 / 超天数含 14 天边界 / 超体积 / active 三项保护 / 空目录 / 去重 / 真实通勤量级 / 默认值对齐 `TuningConfig` / 迁移判定 5 项）；`gradlew test` **54 项全绿**（原 34 不回退）；
  - **真机验收 7 项全部通过**（2026-09-25，红米 K80，详见 `docs/spec/done/csv-storage-policy/需求.md` 第 7.1 节）：老用户升级保住录制（DataStore 落成显式 `true`）→ 开始监测后 **20 次会话自动清到 1 次**（剩的正是 `activeStamp` 保护的在录会话）+ `replay_report.txt` 保留 + 无孤儿文件 → 设置卡片显示占用 →「立即清理日志」生效 → **清数据（等同新装）后开关为关、日志 0 B、监测不产生文件**；
  - 🔴 **验收中发现并修复了一个会静默改变行为的缺陷**：迁移判据里的 `last_route` 键**是用户会新写入的**，而判定每次启动都重跑 → **全新安装的用户选过一次路线后，第二次打开 App 就被误判成老用户、静默打开录制**。修法：新增一次性标记键 `record_csv_migrated`（一次判定后不再重判），判据抽成纯函数 `core/retention/shouldMigrateRecordCsv(...)` 并补 5 项回归（含端到端「新装 → 选路线 → 二次启动」）；实测确认二次启动后仍无 `record_csv` 键、`logs` 为空。
    **⚠️ 通用教训**：任何「读-判断-写」式迁移，判据里若含**用户后续会写入的键**，必须配一次性标记，否则判定会随使用而漂移。
- **ANR 修复（2026-09-26）**：设备 ANR 堆栈确认 `MonitorService.onCreate()` 内 `preloadSettings()` 的 `runBlocking` 等待 DataStore，导致主线程卡住并延迟 `startForeground`；已改为先进入前台，再由 IO 协程加载设置。设置准备期间的服务动作会排队，停止动作会清空待执行动作。`gradlew test :app:assembleDebug` 已通过，并已通过 `adb install -r` 保留数据更新至红米 K80；待用户手动复测开始监测。未改算法路径、监测循环、传感器或系统级配置；通勤实测有效。
- ⏸ **当前状态（2026-09-26）**：启动 ANR 修复已构建并安装，待用户手动验收；UI 拆「监测 / 设置 / 调试」三 Tab 仍列于 `docs/README.md`「下一步」，待单独实施与验收；其余新需求继续等通勤实测与标定数据回传。
- 🚫 **通勤实测数据回传前的禁止事项（红线，2026-09-25 约定）**——违反任一条，通勤实测结果即作废、必须重测：
  1. **禁止改动算法路径**：`core/fsm/`、`core/feature/`、`core/model/TuningConfig.kt`（含新增 / 调整任何阈值）、`platform/sensor/SensorCollector.kt`、`MonitorService` 的监测循环；
  2. **禁止卸载 App / 清除 App 数据 / 手动删除 `logs/` 内文件**（含调试面板「立即清理日志」按钮）——会丢实测准备状态与待回传的录制数据；装机只允许 `adb install -r`；
  3. **禁止改已定稿的系统级配置**：通知渠道属性（硬规则 4）、前台服务类型 `specialUse`、包名、权限清单；
  4. **任何改动完成后必须自检，不通过不得装机**：`git diff --stat` 确认第 1 条路径零改动 + `gradlew test` 54 项全绿。
- **需要用户配合的通勤实测清单**（一次出行同时完成 M1 验收 + 步态标定取数 + 锁屏压测）：
  1. 出门前：保活五步已做、通知权限已给，在底部**「设置」tab**里打开「记录 CSV」、路线选好（或直接用磁贴走上次路线）、**手机放口袋**（别拿手里 / 别外放音乐，会抬高 vib 基线）；
  2. **关键一步：在站厅就点「开始监测」** → 走去站台 → 等车 → 上车 → 坐完整段。这样一段 CSV 里同时含「站厅走路 + 站台等待 + 真实乘车（启动/匀速/制动/停稳）」，正好供 `gait-discrimination` 标定；
  3. 行程中记下：实际坐了几站（含目的站）、**D−1 提醒出现在第几站**、从停稳到响提醒大约多久、有没有误报/漏报；期间手机锁屏放着即可（顺带完成 10 min 50 Hz 压测）；
  4. 到站后确认：App 计数是否 = 实际站数、常驻通知是否已自动消失；
  5. 回传：说一句「录好了」由 AI 用 `adb pull` 取数（Git Bash 需 `MSYS_NO_PATHCONV=1`），或自行导出；
  6. 若中途服务被杀 / 无提醒 → 记下大致时间点，便于对着 CSV 定位。
- **再下一步**：
  1. 用户下次通勤做**真实线路实测**（≥5 站），**顺带录标定数据**（站厅→站台走路 2 min；真实乘车一段含启动/匀速/制动/停稳），把 CSV 导出 → 回放比对 → 结果填 `需求与方案.md` 第十节验收表；
  2. 标定数据到手后做 `gait-discrimination`；
  3. 之后进 **S3（M2）**：全部通知文案终稿 + 首次启动强引导 + `FOREGROUND_SERVICE_IMMEDIATE` 细化；`trip-history-db` 随 M3。
- **⚠️ 通勤实测前必做**：设置里**打开「记录 CSV」**——该开关默认值已改为关（`csv-storage-policy`），不打开就录不到标定数据。
- **本机设备当前状态**（2026-09-25 验收后）：App 数据已清空（等同新装）→ 路线已重选（示例南站 → 示范路站，k=1）、`record_csv` **未开**、`logs` 空、`record_csv_migrated` 标记已落。
- **本机命令速查与踩坑**：见 `docs/development.md`。**注意 HyperOS 4 beta 已禁 shell 注入按键与 `pm grant`，屏幕操作必须人工**；Git Bash 下 `/sdcard/...` 要加 `MSYS_NO_PATHCONV=1`。
- **接手阅读顺序**：本文件 → `docs/README.md`（需求速览 + 自举环境 + 下一步）→ `docs/spec/active/<当前需求>/需求.md`（当前：`mvp-stop-counter` 收尾 + `gait-discrimination` + `trip-history-db`；`csv-storage-policy` 已 `done`）→ 需要背景时读 `docs/spec/需求与方案.md`。
- **分工**：AI 负责写代码与在本机代敲命令（会话内环境变量可能不生效，用绝对路径）；用户负责手机端操作、通勤实测与验收反馈。

## 入口

- 文档索引 + 需求速览表 + 自举环境：`docs/README.md`
- 跨期总纲（背景 / 范围 / 架构 / 状态机规格 / 里程碑 / 后续优化）：`docs/spec/需求与方案.md`
- spec 目录约定与状态规范：`docs/spec/README.md`
- 开发中的需求：`docs/spec/active/<需求>/`；已完成：`docs/spec/done/`；挂起：`docs/spec/suspended/`
- 构建 / 调试 / 踩坑：`docs/development.md`
