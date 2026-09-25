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
| `development.md` | 构建 / 调试 / 踩坑（含 Gradle 版本应急旋钮、国内镜像） |
| 本文件 | **需求速览表（唯一清单）** + 自举环境 |

## 需求速览（唯一清单）

| 需求 | 状态 | 位置 | 备注 |
|---|---|---|---|
| MVP 核心数站提醒 | `in-progress` | `spec/active/mvp-stop-counter/` | **M1 工程验收已通过**（34 项测试全绿；磁贴一键开始、CSV 回放与现场逐毫秒一致、30 s 自动结束、通知渠道定稿均真机实测通过）；剩余：**真实地铁 ≥5 站实测** + 锁屏 10 min 50 Hz 压测 |
| 步态识别与首站判定修正 | `ready` | `spec/active/gait-discrimination/` | 修「站台走路被误判成车上中途开始 → 多算 1 站」；用步频谱峰 + 周期性 + 陀螺仪，零新增权限；**待用户补录走路 / 乘车 CSV 标定阈值** |
| CSV 记录默认关闭与自动清理 | `in-progress` | `spec/active/csv-storage-policy/` | **代码 + 单测完成**（`gradlew test` 49 项全绿，新增 15 项）；默认不再记录 + 自动保留最近 10 次 / 14 天 / 200 MB；老用户一次性迁移已实测生效；**待手机端 4 项人工验收**（见需求文档第七节） |
| 行程历史数据库与历史列表 | `planned` | `spec/active/trip-history-db/` | SQLite 存**行程摘要**（与 CSV 解耦，CSV 删了摘要仍在）+ 最简历史列表；归 M3；**待决策 5 项**（见文档第四节） |
| S0 框架与文档 | `done` | `spec/done/s0-scaffold-and-docs/` | 目录 / AI 文档 / Gradle 骨架 |
| 线路自助管理、换乘路线、V1.5 围栏自动开始、V2 习惯学习、上岛等 | — | — | 尚未立项（见总纲第十一节「后续优化」）；立项时在 `spec/active/` 建目录 |

挂起：暂无（目录 `spec/suspended/`）。

> 状态含义与流转见 `spec/README.md`。**状态变更必须三处联动**：目录 / frontmatter / 本表。

## 自举环境（新会话必读，本机 2026-09-25 实测）

- **S1 已完成**，本机实际落点：
  1. **JDK 17**：`D:\Java\jdk-17.0.20.1+1`（系统级 `JAVA_HOME` 已存在）；验证 `java -version` 显示 17.0.20.1。
  2. **Android cmdline-tools**：`D:\Android\sdk\cmdline-tools\latest\`（含 `android.exe` 新 CLI）；已装 `platform-tools` 37.0.1、`platforms;android-36`、`build-tools;36.0.0` + `35.0.0`；许可已接受（`D:\Android\sdk\licenses\`）；`ANDROID_HOME=D:\Android\sdk`（用户级）。
     - 注意：新 cmdline-tools 已废弃 `sdkmanager --licenses`，改用 `android.exe sdk install <包>`。
  3. **Gradle 8.13（免安装）**：`D:\Android\gradle-8.13`；项目 wrapper 已生成，此后只用 `gradlew.bat`（Git Bash 用 `./gradlew`）。
- **首次编译已验证通过**：`gradlew.bat :app:assembleDebug` → `app\build\outputs\apk\debug\app-debug.apk`。
- **路径提醒**：本项目在 `D:\Code\own-project\metro-stop`，常不在 IDE 工作区内；新会话若写入受限，先把 IDE 工作区切到本目录。
- **会话内环境变量可能不生效**：直接用绝对路径调用，如 `D:\Android\sdk\platform-tools\adb.exe`、`D:\Android\gradle-8.13\bin\gradle.bat`；跑 Gradle 命令时若报找不到 java，先 `JAVA_HOME=D:\Java\jdk-17.0.20.1+1`。
- **国内网络（可选）**：依赖下载慢时，在 `settings.gradle.kts` 的仓库前加阿里云镜像（`https://maven.aliyun.com/repository/google`、`.../public`、`.../gradle-plugin`）。

## 下一步

**M1（S2）工程验收已通过**（2026-09-25 红米 K80 真机）→ 当前待办：

> ✅ **暂停已解除**：原「等通勤实测期间不改代码」的决定作废，用户 2026-09-25 指示继续开发。
> `csv-storage-policy` 已完成代码与单测，仅剩手机端人工验收（见下 #3）。

| # | 待办 | 说明 |
|---|---|---|
| 1 | **真实地铁 ≥5 站实测 + 补录标定数据** | 用户下次通勤执行：开始监测 → 记实际站数 vs 识别站数（误差 ≤1）、D−1 提醒延迟、耗电；**顺带录两段标定数据**：站厅→站台走路 2 min、真实乘车一段（含启动/匀速/制动/停稳）；完成后导出 CSV 并填 `spec/需求与方案.md` 第十节验收表 |
| 2 | 锁屏 10 分钟 50 Hz 压测 | 验证灭屏后不降频、无 `DATA_GAP` |
| 3 | **`csv-storage-policy` 手机端人工验收** | 代码已完成（49 项测试全绿、装机）；待验 4 项：① 设置卡片显示占用且「记录 CSV」为开（老用户迁移）；② 开始监测后 `logs` 从 20 次会话降到 ≤10 次且 `replay_report.txt` 保留；③ 关开关不产新文件、重开产三件套；④「立即清理日志」生效。详见需求文档第七节 |
| 4 | **`gait-discrimination`（待第 1 项的标定数据）** | 步态识别取代「开始时是否在动」的幅度判据，修「站台走路 → 多算一站」 |
| 5 | 进 **S3（M2）** | 全部通知文案终稿 + 首次启动强引导 + 通知按钮完善 + 保活向导细化 + `FOREGROUND_SERVICE_IMMEDIATE` |
| 6 | **`trip-history-db`（M3）** | SQLite 行程摘要 + 最简历史列表；开工前先定文档第四节的 5 项待决策 |

**M1 实测要点（已验证）**：磁贴一键开始无需打开 App；静置基线 vib 0.0083 / 阈值 0.08（10 倍余量）；3 站行程计数 3/3；`n=k` 后 30.006 s 精确自动结束；19 条回放事件 18 条时间戳逐毫秒一致。

**实测中修复的 5 个缺陷**（含 2 个致命：缓刹漏检、磁贴启动自杀）详见 `spec/active/mvp-stop-counter/验收记录.md`。

详细交接见 `AGENTS.md`「当前进度与交接」。里程碑与验收见 `spec/需求与方案.md` 第九节。

> **注意（新会话必读）**：HyperOS 4 beta 已禁止 adb shell 注入按键（`input keyevent`）与 `pm grant`，**解锁 / 授权必须人工在手机上操作**；Git Bash 下 `/sdcard/...` 需加 `MSYS_NO_PATHCONV=1`。命令速查见 `development.md`。

### 新会话接手（第一句照抄即可）

> 先读 `AGENTS.md` 和 `docs/README.md`，再按 `docs/README.md` 的「下一步」继续（当前：`csv-storage-policy` 代码完成待手机端验收；等通勤实测的标定 CSV 以推进 `gait-discrimination`；之后进 M2）。
