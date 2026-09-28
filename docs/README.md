# docs 开发文档索引

「到站了」的开发文档**全部在 `docs/`**。新会话 / 新接手者（AI 或人）的入口就是本文件：先读「自举环境」，再按「读什么」进具体文档。

## 读什么

| 文件 | 内容 |
|---|---|
| `spec/需求与方案.md` | **跨期总纲**（必读）：背景 / 范围 / 技术选型 / 架构 / 里程碑总表 / 后续优化 / 风险 |
| `spec/README.md` | spec 目录约定与状态规范（命名 / 状态 / frontmatter / 搬家规则）——**只讲规则，不列清单** |
| `spec/active/<需求>/` | 开发中的需求文档（一个需求一个目录，含子文档与资源） |
| `spec/done/<需求>/` | 已完成并验收的需求（内容冻结） |
| `spec/suspended/<需求>/` | 挂起的需求（含搁置原因与恢复条件） |
| **`进度与交接.md`** | **当前进度 / 交接状态的唯一详细来源**：已完成什么、下一步、需要用户配合的事、红线与通勤实测清单、本机设备与环境状态（`AGENTS.md` 只留规则与指针） |
| `development.md` | 构建 / 调试 / 踩坑（含 Gradle 版本应急旋钮、国内镜像、环境安装流程） |
| 本文件 | **需求速览表（唯一清单）** + 自举环境 |

## 需求速览（唯一清单）

| 需求 | 状态 | 位置 | 备注 |
|---|---|---|---|
| MVP 核心数站提醒 | `in-progress` | `spec/active/mvp-stop-counter/` | **检测器 v2 已上线（2026-09-28）**：早通勤真实数据重标阈值 + 乘车证据门 + 纠错补发提醒，回放验证 leg1 12 站全自动计数全对、leg2 2/2 全对零操作；剩余：**下一趟通勤验证（v3 版本）** + 锁屏 10 min 压测 |
| **多信号到站判定 v3（步态门+站姿乘车+UI中文化）** | `in-progress` | `spec/active/multi-signal-detector-v3/` | **2026-09-28 晚实现完成并装机**：步态门 `GaitGate`（gyro 1s RMS，走路不污染证据带）+ 通道 B 站姿乘车（150 s 窗带内占比 ≥48% / 45~48%+H 冲高 / 满窗规则）+ 预热静稳否决（修站台误置 startedInMotion）+ **UI 术语中文化**（状态卡/通知/调试面板）；早通勤回放 12/12、晚通勤回放 12/12（验收 ≥11/12 达标），**99 项测试全绿**；残余：区间信号停车误计（归指纹方案）；**已提交（d1e7c6e）并装机，待通勤验收（与指纹采集并行）** |
| **蜂窝+Wi-Fi 指纹辅助验证** | `in-progress` | `spec/active/cellular-wifi-fingerprint-validate/` | **2026-09-29 凌晨采集器两流实施完成并装机**：lab 新增 `wifi`（BSSID SHA-1 哈希快照 + DIRECT- 计数，1 Hz 节流）/ `cellid`（小区序列，变化即写）两流；manifest 加 `NEARBY_WIFI_DEVICES`（neverForLocation）+ `CHANGE_WIFI_STATE`；**105 项测试全绿**（99 基线 + 6 新增）；红线零改动、`adb install -r` 装机成功；**待：用户授权 NEARBY_WIFI_DEVICES → 明早通勤采集 → H1~H4 逐条裁决（v4 / 辅助票 / 关闭）** |
| 步态识别与首站判定修正 | `in-progress` | `spec/active/gait-discrimination/` | **2026-09-28 部分实施**：乘车证据门 + 阈值重标 + 静稳跨度化已落地（误报根因分析的「滑动窗+单段持续」方案）；剩余：步频谱峰 / 周期性判据（陀螺数据已有，待下批数据验证）；**陀螺步态门部分已并入 v3 需求** |
| CSV 记录默认关闭与自动清理 | `done` | `spec/done/csv-storage-policy/` | **2026-09-25 真机验收全部通过**（54 项测试全绿）；默认不记录 + 自动保留最近 10 次 / 14 天 / 200 MB；实测 20 次会话 → 1 次、报告保留、新装默认关且二次启动不被误判 |
| 行程历史数据库与历史列表 | `planned` | `spec/active/trip-history-db/` | SQLite 存**行程摘要**（与 CSV 解耦，CSV 删了摘要仍在）+ 最简历史列表；归 M3；**待决策 5 项**（见文档第四节） |
| UI 滑动与下拉框掉帧诊断与修复 | `in-progress` | `spec/active/ui-jank-diagnosis/` | **诊断取证 + P0+P1 修复已完成**（2026-09-27，外观 v3：原版字段 + 覆盖式菜单）：下拉框换掉 Popup 机制改同窗口覆盖浮层（janky 27~38% → **0.44~0.52%**，互切单点直切不吞点）；1Hz 更新拆流/订阅下沉/秒表 TextView + 服务两路上报合一与通知签名去重（静止 41.7% → 25~30% 残余为秒表走字，滑动 6.6% → **4.8%**）；54 项测试全绿、红线零改动、已装机；**待用户主观验收** |
| 实验室数据采集（通勤标定与判据裁决） | `in-progress` | `spec/active/lab-data-collection/` | **数据已回传并分析完毕（2026-09-28）**：① vibRunTh/vibStopTh 真实车厢重标完成（v2 落地）；② 步态金标准样本已采（走路/楼梯/乘车全场景 22 个标记）；③ **定位裁决：隧道内 GNSS 全灭、network 档 500 m 冻结——定位不可用，该方向关闭**。待办：需求正式下线（UI 移除 + 权限回收） |
| **真实线路数据（成都 4/6 号线）** | `in-progress` | `spec/active/real-line-data/` | **2026-09-28 立项并实施**（总纲 S5 提前）：用户提供 OSM 全量数据（436 站 / WGS-84）→ 本次只落 **4 号线（30 站）+ 6 号线（56 站）**；三源交叉核对站序；大列表下拉改 `LazyColumn`；资产回归测试锁定「观东→玉双路 12 站 / 玉双路→太升南路 2 站」；**待 2026-09-28 早通勤实测** |
| **换乘路线（多段行程）** | `planned` | `spec/active/transfer-route/` | 用户上下班各换乘一次（6 号线 → 4 号线，反向亦然）；总站数 = Σ各段（12+2=14）、换乘站提醒、一键反向；**涉算法路径（计数/提醒语义），待通勤实测回传与红线解除后开工**；8 项待决策见文档第四节 |
| S0 框架与文档 | `done` | `spec/done/s0-scaffold-and-docs/` | 目录 / AI 文档 / Gradle 骨架 |
| 线路自助管理、V1.5 围栏自动开始、V2 习惯学习、上岛等 | — | — | 尚未立项（见总纲第十一节「后续优化」）；立项时在 `spec/active/` 建目录。注：线路/站点自助管理立项时需一并评估「线路数据 JSON → SQLite」迁移（见 `real-line-data/需求.md` 第六节） |

挂起：暂无（目录 `spec/suspended/`）。

> 状态含义与流转见 `spec/README.md`。**状态变更必须三处联动**：目录 / frontmatter / 本表。

## 自举环境（新会话必读，本机 2026-09-28 两机实测）

- **⚠️ 本机有不止一台开发机**（2026-09-28 用户更换电脑后，新旧两台都在用）：**安装落点刻意保持一致**（下列路径两机相同），便于沿用所有历史文档与命令。
- **⚠️ 仓库所在路径两机不同**（旧机 `D:\Code\own-project\metro-stop`、新机 `D:\Code\home\own-project\metro-stop`）：**任何时候以 `git rev-parse --show-toplevel` 的输出为准**，不要假定路径。新会话若写入受限，先把 IDE 工作区切到仓库根。
- **环境落点**（2026-09-28 在新机上从零重装实测走通；完整安装流程见 `development.md`「环境安装流程」）：
  1. **JDK 17**：`D:\Java\jdk-17.0.20.1+1`（Temurin，`java -version` 显示 17.0.20.1）；用户级 `JAVA_HOME` 已设。
  2. **Android SDK**：`D:\Android\sdk`（`ANDROID_HOME` / `ANDROID_SDK_ROOT` 用户级）；cmdline-tools **19.0** 位于 `cmdline-tools\latest\`，**只有 `sdkmanager.bat`**（旧文档提到的 `android.exe` 新 CLI 本机没有，`sdkmanager --install <包>` 等价可用）；已装 `platform-tools` 37.0.1、`platforms;android-36`、`build-tools;36.0.0` + `35.0.0`；许可文件在 `D:\Android\sdk\licenses\android-sdk-license`（手写，无需 `--licenses`）。
  3. **Gradle 8.13（免安装）**：`D:\Android\gradle-8.13\`；日常仍用项目 wrapper `gradlew.bat`（Git Bash 用 `./gradlew`）。
  4. **Python 3.12.9**：`C:\Users\JF\python3\python`（`docs/spec/**/assets/*.py` 只用标准库，无第三方依赖）。
- **已验证**：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk`（12.0 MB），**79 项测试全绿**（`--rerun-tasks` 强制重跑复核过）。
- **环境变量注意**：`JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` 与 JDK、platform-tools、cmdline-tools 的 `bin` 已写进用户级 PATH；**但已运行的进程（含 AI 会话）拿不到新值，须用绝对路径或先 `export JAVA_HOME=...`**。
- **国内网络（必读，2026-09-28 实测）**：本机**到 github.com 的连接超时**，而官方源会把大构件 301 重定向到 GitHub —— 已在**机器级** `C:\Users\JF\.gradle\init.d\mirrors.gradle` 注入阿里云镜像（`repository/public` + `repository/google`，排在官方源之前，不动仓库文件）。Gradle 发行版与 JDK 也从镜像下载（华为云 `mirrors.huaweicloud.com/gradle/`、清华 Adoptium）。详见 `development.md`「国内网络」。
  - **不要在仓库里改 `settings.gradle.kts` 加镜像**（会污染提交）；机器级 init 脚本对本机所有项目生效。
- **仓库外分析资产（本仓库不收录，由用户手动同步）**：误报根因分析目录（`FINDINGS.md` + `backup/` 会话录制 + `lab-test/`）与 `jank-artifacts\` 掉帧实验数据都**不在仓库内**，用户自行用压缩包/网盘在两台机器间同步；引用它们的历史文档只要注明「仓库外」即可，不要试图在仓库里找。
  - ⚠️ **本 GitHub 仓库是 PUBLIC**：这些资产含**真实 GPS 坐标与个人行程**，**不要提交进仓库**。

## 下一步

**检测器 v3 已实现并装机（2026-09-28 晚，晚通勤站姿数据标定）** → 当前待办：

> 🛠 **环境：新旧两台开发机都已配好（2026-09-28）**，落点一致、构建验证通过；两台机器**仓库路径不同**，一律以 `git rev-parse --show-toplevel` 为准。详见上文「自举环境」。
> - **设备已连接**（2026-09-28 晚，热点 + 无线调试，`adb devices` 可见 `cbc62cf0`）：重连方式 = 电脑开移动热点 → 手机连热点 → 开发者选项「无线调试」→ 配对码配对 → `adb connect`（端口以主界面为准，配对端口 ≠ 连接端口）。
> ✅ **v2 已毕业（2026-09-28）**：早通勤数据完成 v2 标定（阈值重标 + 乘车证据门，leg1 12/12、leg2 2/2），装机后晚通勤实测发现**站姿乘车下 vib 一维失效**（12 组真实停站全被拦）→ 促成 v3。
> ✅ **v3 已提交并装机（2026-09-28 晚实现，`d1e7c6e`）**：步态门 + 通道 B 站姿乘车 + 满窗规则 + 预热静稳否决 + UI 中文化；早通勤回放 12/12、晚通勤回放 12/12（验收 ≥11 达标）、**99 项测试全绿**；`adb install -r` 保留数据装机成功。**⚠️ 通勤实测验收未做**（回放通过后先行提交）。
> 🚫 **v3 通勤验证回传前的新红线**：算法路径 / 清数据 / 系统级配置冻结（`gradlew test` 99 项全绿为装机门槛）；**lab 旁路（采集器两流）不在冻结范围**。

| # | 待办 | 说明 |
|---|---|---|
| 0 | **用户启动 App 看一眼** | 状态卡「状态」应显示中文（预热中/巡航中/已停稳…）、调试面板中英并排（状态/车振(vib)/加减速(H)/已计站(n)） |
| 1 | **通勤前自检两流** | App 调试 Tab → LabCard「授予权限」（现 **4 项**，含 Wi-Fi 附近设备）→「开始采集」确认状态含 `wifi`/`cellid` → 停止；顺带看 v3 中文 UI |
| 2 | **明早通勤一趟两用** | ① v3 验收（冻结参数，任意姿势）：预期 12 站全自动（允许 1 次纠错兜底）、站台等车不计站、D−1 与到站提醒正确；② lab 开采集采指纹数据（场景切换按「📍标记」，重点标「列车进站停稳」）；③ 顺带锁屏 10 min 压测；回传后 → 4 条假设裁决 |
| 3 | **`transfer-route` 8 项待决策** | 换乘功能（6 号线 ⇄ 4 号线）；决策项见 `spec/active/transfer-route/需求.md` 第四节 |
| 4 | **`gait-discrimination` 剩余部分** | 步频谱峰 / 周期性判据验证（陀螺数据已采到；陀螺步态门主体已并入 v3 落地） |
| 5 | 三 Tab 真机验收 | 手动验证冷启动、监测开始 / 停止、三个 Tab 切换与页面滑动；CSV 开关位于「设置」Tab |
| 6 | 进 **S3（M2）** | 全部通知文案终稿 + 首次启动强引导 + 通知按钮完善 + 保活向导细化 + `FOREGROUND_SERVICE_IMMEDIATE` |
| 7 | **`trip-history-db`（M3）** | SQLite 行程摘要 + 最简历史列表；开工前先定文档第四节的 5 项待决策 |

> **⚠️ 下一趟通勤前**：「记录 CSV」保持打开（当前设备已开，v2 装机时数据已恢复）。

**M1 实测要点（已验证）**：磁贴一键开始无需打开 App；静置基线 vib 0.0083 / 阈值 0.08（10 倍余量）；3 站行程计数 3/3；`n=k` 后 30.006 s 精确自动结束；19 条回放事件 18 条时间戳逐毫秒一致。

**实测中修复的 5 个缺陷**（含 2 个致命：缓刹漏检、磁贴启动自杀）详见 `spec/active/mvp-stop-counter/验收记录.md`。

详细交接见 [`进度与交接.md`](进度与交接.md)。里程碑与验收见 `spec/需求与方案.md` 第九节。

> **注意（新会话必读）**：HyperOS 4 beta 已禁止 adb shell 注入按键（`input keyevent`）与 `pm grant`，**解锁 / 授权必须人工在手机上操作**；Git Bash 下 `/sdcard/...` 需加 `MSYS_NO_PATHCONV=1`。命令速查见 `development.md`。

### 新会话接手（第一句照抄即可）

> 先读 `AGENTS.md`（规则）、`docs/README.md`（索引 + 需求速览 + 自举环境）和 `docs/进度与交接.md`（当前进度 / 下一步 / 待用户配合事项），再按「下一步」继续（当前：**采集器两流已装机（105 项测试全绿），待用户授权 NEARBY_WIFI_DEVICES 并自检两流 → 明早通勤一趟两用（v3 验收 + 指纹采集）**；通勤验证回传前算法路径冻结，lab 旁路不在冻结范围）。
