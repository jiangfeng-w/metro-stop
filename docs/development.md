# 构建 / 调试 / 踩坑

> S0 阶段为骨架说明，M1 起持续补实「踩坑表」。

## 常用命令（环境装好后）

```bat
:: 构建 / 安装
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

:: 单元测试（纯 JVM，改任何阈值后必跑）
gradlew.bat test

:: 日志（自定义 TAG）
adb logcat -v time -s StationMonitor:* Feat:* Sm:* Notif:*

:: 导出 CSV 三件套（sensor / events / meta）
adb pull /sdcard/Android/data/com.metrostop.reminder/files/logs .\logs

:: 设备信息（写入 meta.json 用）
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

Git Bash 下把 `gradlew.bat` 换成 `./gradlew`。

## 版本组合与应急旋钮

主推组合（写死在 `gradle/libs.versions.toml`）：JDK 17 · Gradle Wrapper 8.13 · AGP 8.13.0 · Kotlin 2.2.20 · Compose BOM 2026.05.01 · minSdk 26 / compileSdk & targetSdk 36。

构建失败按顺序试，每次只动一处：

1. 报 Compose 编译器与 runtime 不匹配 → Kotlin 与 Compose BOM 同步升降（2.2.20 ↔ BOM 2026.05.01）。
2. 报依赖解析失败（`Could not find androidx.xxx`）→ 到 Google Maven 查该库最新稳定版替换（AndroidX 差一两个小版本不影响构建）。
3. 报 `Unsupported class file major version` → Gradle 用的 JDK 不是 17（检查 `JAVA_HOME` 或会话内 java 绝对路径）。
4. 玄学错误 → `gradlew.bat --stop` 后加 `--refresh-dependencies` 重试。
5. 依赖下载卡住 → `settings.gradle.kts` 加阿里云镜像（见 `docs/README.md`「国内网络」）。

## 传感器 / 服务调试

- 传感器数据实时值看 App 内「实时调试面板」（state / vib / H / n）；
- 「离线回放」：把 sensor CSV push 回 `Android/data/com.metrostop.reminder/files/logs/`，点面板里的回放按钮，与现场事件序列比对。

## 踩坑表

| 日期 | 现象 | 根因 | 解决 |
|---|---|---|---|
| 2026-09-25 | 本机无 `java` / `ANDROID_HOME` / `adb` | 未安装 Android 构建环境 | S1 按 `docs/README.md`「自举环境」安装 |
