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
- **已完成 S1（环境，除手机连接外）**，本机实测落点（新会话直接用绝对路径）：
  - JDK 17：`D:\Java\jdk-17.0.20.1+1`（系统级 `JAVA_HOME` 已存在，命令行 `java -version` 显示 17.0.20.1）；
  - Android SDK：`D:\Android\sdk`（`ANDROID_HOME` 用户级已设）；含 `cmdline-tools\latest\`、`platform-tools` 37.0.1、`platforms\android-36`、`build-tools\36.0.0` **及 35.0.0**（AGP 8.13 默认要 35，勿删）；
  - 许可文件已写入 `D:\Android\sdk\licenses\`（新 cmdline-tools 的 `sdkmanager --licenses` 已废弃，用 `android.exe sdk install`）；
  - Gradle 8.13 免安装：`D:\Android\gradle-8.13`；项目 wrapper 已生成（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`，待入库）；
  - `PATH` 用户级已追加 platform-tools、cmdline-tools\latest\bin、gradle-8.13\bin。
- **已完成首次编译**：`gradlew.bat :app:assembleDebug` 通过（1m35s），产物 `app\build\outputs\apk\debug\app-debug.apk`（12 MB，S0 骨架无业务代码）。
- **S1 已全部完成**：
  - 手机通过**无线调试**连接（`adb devices` 显示 `adb-cbc62cf0-…_adb-tls-connect._tcp  device`，红米 K80 / 24117RK2CC / Android 17）。注意可能同时存在 IP 直连与 mDNS 两条通道，若 `adb` 报 "more than one device"，用 `adb disconnect <ip:port>` 清掉多余通道。
  - 「USB 安装」开关已在手机端打开，`adb install -r` 通过：`com.metrostop.reminder` 0.1.0 已装机（注意：S0 骨架无 launcher activity，桌面上无图标属正常）。
- **再下一步 S2（M1）**：按 `docs/spec/active/mvp-stop-counter/需求.md` 写代码（第 1/3/4/5/6 项：路线选择 UI、前台服务+传感器、状态机、CSV、调试面板+回放）。
- **接手阅读顺序**：本文件 → `docs/README.md`（需求速览 + 自举环境 + 下一步）→ `docs/spec/active/mvp-stop-counter/需求.md`（当前需求）→ 需要背景时读 `docs/spec/需求与方案.md`。
- **分工**：AI 负责写代码与在本机代敲命令（会话内环境变量可能不生效，用绝对路径）；用户负责手机端操作、通勤实测与验收反馈。

## 入口

- 文档索引 + 需求速览表 + 自举环境：`docs/README.md`
- 跨期总纲（背景 / 范围 / 架构 / 状态机规格 / 里程碑 / 后续优化）：`docs/spec/需求与方案.md`
- spec 目录约定与状态规范：`docs/spec/README.md`
- 开发中的需求：`docs/spec/active/<需求>/`；已完成：`docs/spec/done/`；挂起：`docs/spec/suspended/`
- 构建 / 调试 / 踩坑：`docs/development.md`
