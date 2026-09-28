# 到站了（metro-stop）

个人自用的 Android 地铁到站提醒：**不依赖定位**，用加速度传感器识别列车进站并数站，在目的站前一站与到站时发出通知 + 震动提醒，防止坐过站。

- 目标设备：红米 K80（HyperOS 4 beta / Android 17），adb 侧载安装，不上架。
- 当前状态：**M1 工程验收已通过；真实线路（成都 4/6 号线）已上线；等通勤实测数据回传**（详见 [`docs/进度与交接.md`](docs/进度与交接.md)）。

## 文档

- 入口（文档索引 + 需求速览 + 环境自举 + 构建命令）：[`docs/README.md`](docs/README.md)
- **进度与交接（当前进度 / 下一步 / 待用户配合 / 红线）**：[`docs/进度与交接.md`](docs/进度与交接.md)
- 跨期总纲（需求 / 架构 / 状态机规格 / 里程碑）：[`docs/spec/需求与方案.md`](docs/spec/需求与方案.md)
- AI 协作规范（硬性规则 / 技术栈 / 提交规范 / 分工）：[`AGENTS.md`](AGENTS.md)

## 构建与安装（需先按 `docs/README.md` 装好 JDK 17 + Android cmdline-tools）

```bat
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
