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
| MVP 核心数站提醒 | `ready` | `spec/active/mvp-stop-counter/` | 数站 + 目的站前一站/到站提醒 + 磁贴一键开始；S1 环境已就绪，待开工 M1 |
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

**S1 已全部完成**（环境 + 无线调试 + 装机验证：`com.metrostop.reminder` 0.1.0 已侧载到红米 K80）→ **S2 开发 M1**（按 `spec/active/mvp-stop-counter/需求.md` 写代码：路线选择 UI / 前台服务+传感器 / 状态机 / CSV / 调试面板+回放）。里程碑与验收见 `spec/需求与方案.md` 第九节。

### 新会话接手（第一句照抄即可）

> 先读 `AGENTS.md` 和 `docs/README.md`，再按 `docs/README.md` 的「下一步」继续（当前：S1 已完成，S2 可开工 M1）。

更细的进度与交接见 `AGENTS.md`「当前进度与交接」。
