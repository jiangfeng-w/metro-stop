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
| MVP 核心数站提醒 | `done` | `spec/done/mvp-stop-counter/` | **2026-09-30 归档**：M1 主链路验收（通勤 ≥5 站误差 ≤1）被 v4 达标趟（09-30 早 14/14、0 纠错）事实覆盖（检测器 v1→v2→v3→v4 演进见各自文档）；M2 通知文案终稿 + 结束通知已落地（09-29）；**遗留：锁屏 10 min 压测未做，挪总纲后续优化节后补** |
| **多信号到站判定 v3（步态门+站姿乘车+UI中文化）** | `done` | `spec/done/multi-signal-detector-v3/` | **2026-09-30 归档（毕业语义）**：真机现场验收未达标（早 9 按键 / 晚 7 按键双向漂移），裁决「纯 IMU 路线触顶」；两批回放资产 + GaitGate + UI 中文化保留生效，主链路由 **v4 接棒** |
| **蜂窝+Wi-Fi 指纹辅助验证** | `suspended` | `spec/suspended/cellular-wifi-fingerprint-validate/` | **2026-09-30 调查使命完成并挂起**：H3 蜂窝分区成立已并入 v4（12/12 站区覆盖）；定位裁决产出（隧道内定位不可用、方向关闭）；wifi 悬案定论 = HyperOS 4 beta 的 WifiManager 兼容层对第三方 App 异常/抑制（`wifi_mgr=false` vs `settings_on=1` 恒分离，shell 扫描正常）→ **H1/H2/H4 关闭（工具不可用非否定）**；**恢复条件 = 系统更新后凭 `WIFI_DIAG` 重验**（诊断事件保留在采集器中） |
| **检测器 v4（蜂窝分区主导 + IMU 定时刻）** | `in-progress` | `spec/active/cell-zone-detector-v4/` | **早通勤达标（09-30 早，0 纠错）；晚通勤 12/12 计数对但 3 纠错不达标（根因 = rideCount=1 映射泛化 + 融合层两 bug，非传感器问题）**；4 方向映射各 rideCount=1（cd4_to_xihe 09-30 晚落表）；**外部评审四文档 + AI 复核已入目录，批次 A/B/C 定稿**（gNB 键折算 = 跨天泛化主力修法 82%→93%；`Int.MAX_VALUE` 伪小区缺陷待修；701 疑似「假共享」待多趟重判）；遗留节后：到站时刻漂移 + 小区流断档（「比高德慢」根因）、IMU 状态显示错乱、融合层两 bug；**126 项全绿**；节中 `--merge` + 泛化回放 + rssi 挖掘（目标已更正：判停动出局）+ 批次 B |
| **Wi-Fi 采集器重扫风暴修复** | `suspended` | `spec/suspended/wifi-collector-fix/` | **2026-09-30 挂起**：修复已交付且生效（失败事件 65 万→5 条 ✓，重扫风暴治愈）；「非空快照 >0」经四轮对照实验定性无法在本系统达成——系 HyperOS 4 beta 的 WifiManager 兼容层异常，**非采集器缺陷**（详见 `cellular-wifi-fingerprint-validate` 第八节）；**恢复条件 = 系统更新后凭 `WIFI_DIAG` 重验**，通过即收尾 done |
| 步态识别与首站判定修正 | `suspended` | `spec/suspended/gait-discrimination/` | **2026-09-30 挂起**：陀螺步态门部分已并入 v3（`GaitGate` 落地）；剩余步频谱峰/周期性判据被数据裁决证伪（真实走路 0% 检出、手摇误检 11~32%），重验证前不可上线；v4 蜂窝主导后优先级降低。**恢复条件**：v4 稳定后需 IMU 侧增强再启 |
| CSV 记录默认关闭与自动清理 | `done` | `spec/done/csv-storage-policy/` | **2026-09-25 真机验收全部通过**（54 项测试全绿）；默认不记录 + 自动保留最近 10 次 / 14 天 / 200 MB；实测 20 次会话 → 1 次、报告保留、新装默认关且二次启动不被误判 |
| 行程历史数据库与历史列表 | `planned` | `spec/active/trip-history-db/` | SQLite 存**行程摘要**（与 CSV 解耦，CSV 删了摘要仍在）+ 最简历史列表；归 M3；**待决策 5 项**（见文档第四节） |
| UI 滑动与下拉框掉帧诊断与修复 | `in-progress` | `spec/active/ui-jank-diagnosis/` | **诊断取证 + P0+P1 修复已完成**（2026-09-27，外观 v3：原版字段 + 覆盖式菜单）：下拉框换掉 Popup 机制改同窗口覆盖浮层（janky 27~38% → **0.44~0.52%**，互切单点直切不吞点）；1Hz 更新拆流/订阅下沉/秒表 TextView + 服务两路上报合一与通知签名去重（静止 41.7% → 25~30% 残余为秒表走字，滑动 6.6% → **4.8%**）；54 项测试全绿、红线零改动、已装机；**待用户主观验收** |
| 实验室数据采集（通勤标定与判据裁决） | `in-progress` | `spec/active/lab-data-collection/` | **数据已回传并分析完毕（2026-09-28）**：① vibRunTh/vibStopTh 真实车厢重标完成（v2 落地）；② 步态金标准样本已采（走路/楼梯/乘车全场景 22 个标记）；③ **定位裁决：隧道内 GNSS 全灭、network 档 500 m 冻结——定位不可用，该方向关闭**。待办：需求正式下线（UI 移除 + 权限回收） |
| **真实线路数据（成都 4/6 号线）** | `done` | `spec/done/real-line-data/` | **2026-09-30 归档**：2026-09-28 立项并实施（总纲 S5 提前）：OSM 全量数据（436 站 / WGS-84）→ 4 号线（30 站）+ 6 号线（56 站）；三源交叉核对；大列表 `LazyColumn`；资产回归锁定「观东→玉双路 12 站 / 玉双路→太升南路 2 站」；装机核验 + 09-28 起各趟通勤实测使用正常（含 09-30 早达标趟） |
| **换乘路线（多段行程）** | `planned` | `spec/active/transfer-route/` | 用户上下班各换乘一次（6 号线 → 4 号线，反向亦然）；总站数 = Σ各段（12+2=14）、换乘站提醒、一键反向；**涉算法路径（计数/提醒语义），待通勤实测回传与红线解除后开工**；8 项待决策见文档第四节 |
| S0 框架与文档 | `done` | `spec/done/s0-scaffold-and-docs/` | 目录 / AI 文档 / Gradle 骨架 |
| 线路自助管理、V1.5 围栏自动开始、V2 习惯学习、上岛等 | — | — | 尚未立项（见总纲第十一节「后续优化」）；立项时在 `spec/active/` 建目录。注：线路/站点自助管理立项时需一并评估「线路数据 JSON → SQLite」迁移（见 `real-line-data/需求.md` 第六节） |

挂起：`gait-discrimination`、`wifi-collector-fix`、`cellular-wifi-fingerprint-validate`（后两个 2026-09-30 挂起：wifi 悬案已定论为 HyperOS 兼容层异常，恢复条件 = 系统更新后凭 `WIFI_DIAG` 重验；目录 `spec/suspended/`）。

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
  - **通勤数据资产两机位置（2026-09-30 归整定型；明细见 `进度与交接.md` 第三节「分析资产」）**：**新机** `D:\01_TempFiles\metro\analysis\`（按日期六目录 + `backup\` / `archive\`）；**旧机** `D:\01Portal\到站了\`（按日期八目录 + `archive\`）——**旧机没有 `D:\01_TempFiles\`**，新机晚通勤数据尚未回传；在两机中的哪台跑会话，就去那台的目录找数据。

## 下一步

**v4 已立项（2026-09-29，用户批准方案 A「蜂窝分区主导 + IMU 定时刻」）→ 当前待办：**

> 🛠 **环境**：两台开发机均已配好（落点一致、路径不同，以 `git rev-parse --show-toplevel` 为准）；新机用户级 `JAVA_HOME` 已生效（2026-09-29 重启后验证，会话内可直接 `gradlew`）。设备连接：USB / 无线调试均可，`adb devices` 以绝对路径 `D:\Android\sdk\platform-tools\adb.exe` 调用。

| # | 待办 | 说明 |
|---|---|---|
| 0 | ✅ ~~装机 wifi-collector-fix~~ | 已完成（2026-09-29 11:40，`adb install -r` 保留数据成功，dumpsys 核验） |
| 1 | ✅ ~~今晚通勤 = 学习期第 2 趟 + wifi 重测~~ | 已完成并回传（2026-09-29 夜）：MARK 13/14（仅换乘玉双路漏标，6 号线 12 站全齐）；lab 全量 + 两段监测；`cd6_to_lanjiagou` 映射已落表（rideCount=1）；wifi 重测失败事件达标但快照全空（转系统诊断） |
| 2 | ✅ ~~今早 CSV 入 replay 资产~~ | 已完成（2026-09-29：sensor+cells 四件入 `replay/`，`RealCommuteMorningRegressionTest` 按 12 站 MARK 真值锁定） |
| 3 | ✅ ~~v4 实施~~ | 已完成（2026-09-29：**119 项测试全绿**，早通勤回放 12/12 零误计一次通过，影子模式装机 16:40；步数辅助通道留待主通道验证后并入） |
| 4 | ✅ ~~用户拍板：影子模式提前 D−1 保留 or gate~~ | 已 gate（2026-09-29 夜）：`onCellSample` 提前 D−1 加 `cellZoneGateEnabled` 检查，影子完全 = v3；`cleanTest test` 强制重跑 119 项全绿 + `adb install -r` 装机 22:41；连带发现切主通道前置缺口「gate 开 + 有映射 + 小区流缺席 → 0 计数」（见 v4 需求变更记录） |
| 5 | ✅ ~~v4 主通道首次实测（节前最后一天早通勤）~~ | **✅ 验收达标（2026-09-30 午间裁决）**：12/12 + 2/2 计数全对、**0 纠错按键**、三级提醒全按序；遗留三问题归档节后（701 共享区连锁 / 到站时刻 ±34s 漂移 + 小区流 146 处断档 / 状态显示错乱），详见 v4 需求变更记录 |
| 6 | **外部评审批次 A：定位权限耦合等处置（定稿，各项执行前逐项过用户）** | 四份外部文档 + AI 复核已入 `spec/active/cell-zone-detector-v4/`（调研 / 架构评审 / 设想评审 / 多趟分析 / 复核意见）；**P0-1：`ACCESS_FINE_LOCATION` 是 v4 主链路读 CellInfo 的隐式必需**（manifest 标为 lab 临时例外、下线即移除 → 小区流静默失效）；批次 A 零装机不碰判定路径：A1 规则 3 表述改写三件套（AGENTS 属规则级文件单独确认）、A2 `android.location.*` 引用自检测试、A3 文档数字两处、A4 气压计归档、A5 `ciOrUnknown` 补 `Int.MAX_VALUE` 过滤 + 回归、A6 gen 脚本伪小区断言、A7 需求第十节 rssi 目标更正注（判停动已否定） |
| 7 | **节中（笔记本）：合并 + 泛化回放 + rssi 挖掘 + 外部评审批次 B** | 第 2 趟 `--merge`（≥50% 交集）→ 跨趟泛化回放（A 趟映射验 B 趟 + 反向）；**rssi 挖掘目标已更正**：判停动出局，聚焦站内 vs 隧道分布特征 + TA 同批采集；**批次 B**：B4 701「假共享」假说按 gNB 跨趟重判（9438163 vs 9438162 相邻编号——若证实 4 号线连锁问题直接消失）、B5 TA 采集改 lab（装机采一次通勤，先确认设备支持）、B6 Viterbi 仓库外离线原型 |
| 8 | ✅ ~~wifi 空快照系统层诊断~~ | **✅ 结案（2026-09-30 午间四轮对照实验）**：HyperOS 4 beta 的 WifiManager 兼容层对第三方 App 异常/抑制（`wifi_mgr=false` vs `settings_on=1` 恒分离、shell 扫描正常、neverForLocation / INTERNET 增删均无关）；**H1/H2/H4 关闭（工具不可用非否定）**，节后系统更新后凭 `WIFI_DIAG` 重验；详见 `cellular-wifi-fingerprint-validate` 第八节 |
| 9 | **`transfer-route` 8 项待决策** | 换乘功能（6 号线 ⇄ 4 号线）；决策项见 `spec/active/transfer-route/需求.md` 第四节 |
| 10 | 三 Tab 真机验收 / 锁屏 10 min 压测 | 手动验证；压测可与任一趟通勤并行 |
| 11 | 进 **S3（M2）** / **`trip-history-db`（M3）** | ~~文案引导终稿~~ **通知文案终稿 + 结束通知已完成（2026-09-29 晚）；保活引导文案与首启引导卡暂缓（个人自用，见 `mvp-stop-counter/验收记录.md` 第八节）**；SQLite 行程摘要（先定 5 项待决策） |

**M1 实测要点（已验证）**：磁贴一键开始无需打开 App；静置基线 vib 0.0083 / 阈值 0.08（10 倍余量）；3 站行程计数 3/3；`n=k` 后 30.006 s 精确自动结束；19 条回放事件 18 条时间戳逐毫秒一致。

**实测中修复的 5 个缺陷**（含 2 个致命：缓刹漏检、磁贴启动自杀）详见 `spec/active/mvp-stop-counter/验收记录.md`。

详细交接见 [`进度与交接.md`](进度与交接.md)。里程碑与验收见 `spec/需求与方案.md` 第九节。

> **注意（新会话必读）**：HyperOS 4 beta 已禁止 adb shell 注入按键（`input keyevent`）与 `pm grant`，**解锁 / 授权必须人工在手机上操作**；Git Bash 下 `/sdcard/...` 需加 `MSYS_NO_PATHCONV=1`。命令速查见 `development.md`。

### 新会话接手（第一句照抄即可）

> 先读 `AGENTS.md`（规则）、`docs/README.md`（索引 + 需求速览 + 自举环境）和 `docs/进度与交接.md`（当前进度 / 下一步 / 待用户配合事项），再按「下一步」继续（当前：**09-30 晚通勤已裁决（12/12 计数对但 3 纠错不达标，根因 = 映射泛化 + 融合层两 bug）+ cd4_to_xihe 已落表；外部评审四文档 + 复核已入 v4 目录，批次 A（权限耦合三件套 / ciOrUnknown 修复 / rssi 目标注等 7 项）定稿待逐项执行，gNB 键 = 跨天泛化主力修法（节后 C2）；节中：第 2 趟 `--merge` + rssi 挖掘（判停动已出局）+ 批次 B（701 假共享重判 / TA 采集 / Viterbi 原型）；节后：rideCount=2 映射 + 统一调参 + wifi 重验 + 批次 C 装机**）。
