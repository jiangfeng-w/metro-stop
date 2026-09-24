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
| MVP 核心数站提醒 | `ready` | `spec/active/mvp-stop-counter/` | 数站 + 目的站前一站/到站提醒 + 磁贴一键开始；S1 装环境后开工 |
| S0 框架与文档 | `done` | `spec/done/s0-scaffold-and-docs/` | 目录 / AI 文档 / Gradle 骨架 |
| 线路自助管理、换乘路线、V1.5 围栏自动开始、V2 习惯学习、上岛等 | — | — | 尚未立项（见总纲第十一节「后续优化」）；立项时在 `spec/active/` 建目录 |

挂起：暂无（目录 `spec/suspended/`）。

> 状态含义与流转见 `spec/README.md`。**状态变更必须三处联动**：目录 / frontmatter / 本表。

## 自举环境（新会话必读，本机 2026-09-25 实测）

- **本机当前没有任何 Android 构建环境**：没有 `java`、没有 `ANDROID_HOME`、没有 `adb`、没装 Android Studio。首次构建前需按下面步骤安装（S1）。
- 约定安装方式（轻量命令行，不用 Android Studio）：
  1. **JDK 17**：`winget install EclipseAdoptium.Temurin.17.JDK`（无 winget 则官网 zip 解压）；验证 `java -version` 显示 17.x。
  2. **Android cmdline-tools**：zip 解压到 `D:\Android\sdk\cmdline-tools\latest\`，然后
     `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"` + `sdkmanager --licenses`（全 y）；环境变量 `ANDROID_HOME=D:\Android\sdk`。
  3. **Gradle 8.13（免安装）**：下载 `gradle-8.13-bin.zip` 解压到 `D:\Android\gradle-8.13`；在项目根执行其 `bin\gradle.bat wrapper` 生成 wrapper（此后只用 `gradlew.bat`）。
- **路径提醒**：本项目在 `D:\Code\own-project\metro-stop`，常不在 IDE 工作区内；新会话若写入受限，先把 IDE 工作区切到本目录。
- **会话内环境变量可能不生效**：直接用绝对路径调用，如 `D:\Android\sdk\platform-tools\adb.exe`、`D:\Android\gradle-8.13\bin\gradle.bat`。
- **国内网络（可选）**：依赖下载慢时，在 `settings.gradle.kts` 的仓库前加阿里云镜像（`https://maven.aliyun.com/repository/google`、`.../public`、`.../gradle-plugin`）。

## 下一步

**S1 装环境**（JDK 17 + cmdline-tools + platform-tools + Gradle 8.13 免安装，红米开 USB 调试）→ **S2 开发 M1**（`gradle wrapper` + 首次编译 + 上表 MVP 需求）。里程碑与验收见 `spec/需求与方案.md` 第九节、M1 明细见 `spec/active/mvp-stop-counter/需求.md`。
