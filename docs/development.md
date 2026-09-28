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

## 环境安装流程（2026-09-28 在新机器上实测走通；换机 / 重装照此执行）

```bash
# 0) 准备：装机包统一放 D:\Android\cache\，网络到 GitHub 不通，全部走国内镜像
MIRROR_JDK='https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
MIRROR_GRADLE='https://mirrors.huaweicloud.com/gradle/gradle-8.13-bin.zip'
MIRROR_CMDLINE='https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'

# 1) JDK 17 → D:\Java（解压后自带 jdk-17.0.20.1+1 顶层目录）
unzip -q jdk17.zip -d /d/Java

# 2) cmdline-tools → D:\Android\sdk\cmdline-tools\latest（注意是内层 cmdline-tools 目录改名）
mkdir -p /d/Android/sdk/cmdline-tools
unzip -q cmdline-tools.zip -d /d/Android/sdk/tmp-cli
mv /d/Android/sdk/tmp-cli/cmdline-tools /d/Android/sdk/cmdline-tools/latest

# 3) SDK 许可（手写文件，每行一个哈希）
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n8933bad161af4178b1185d1a37fbf41ea5269c55\n' > /d/Android/sdk/licenses/android-sdk-license

# 4) SDK 组件（分包装：一个包 zip 坏掉不会连累其它包）
export JAVA_HOME='D:\Java\jdk-17.0.20.1+1'; export ANDROID_HOME='D:\Android\sdk'
cd /d/Android/sdk/cmdline-tools/latest/bin
yes | ./sdkmanager.bat --install "platform-tools"
yes | ./sdkmanager.bat --install "platforms;android-36"
yes | ./sdkmanager.bat --install "build-tools;36.0.0" "build-tools;35.0.0"
./sdkmanager.bat --list_installed          # 核对 4 项都在

# 5) Gradle 8.13 免安装版 → D:\Android\gradle-8.13（注意 zip 内多一层，需上移）
unzip -q gradle-8.13-bin.zip -d /d/Android/gradle-8.13 && mv /d/Android/gradle-8.13/gradle-8.13/* /d/Android/gradle-8.13/ && rmdir /d/Android/gradle-8.13/gradle-8.13

# 6) local.properties（仓库外配置，不入库；路径用仓库根，两机不同）
#    在仓库根目录执行：
echo 'sdk.dir=D\:\\Android\\sdk' > local.properties

# 7) 用户级环境变量（PowerShell，写 HKCU\Environment）
#    JAVA_HOME=D:\Java\jdk-17.0.20.1+1 ; ANDROID_HOME=ANDROID_SDK_ROOT=D:\Android\sdk
#    PATH 追加 D:\Java\jdk-17.0.20.1+1\bin ; D:\Android\sdk\platform-tools ;
#              D:\Android\sdk\cmdline-tools\latest\bin ; D:\Android\gradle-8.13\bin
```

## 国内网络（本机到 GitHub 不通，必读）

- **现象**：`compileDebugKotlin` 报 `Could not download kotlin-compiler-embeddable-2.2.20.jar → Connect to github.com:443 failed`。
- **根因**：`repo.maven.apache.org` 与 `services.gradle.org` 对部分大构件 **301 重定向到 `github.com`**，而本机到 GitHub 的连接超时。
- **解法（已在机器级配好，勿改仓库）**：`C:\Users\JF\.gradle\init.d\mirrors.gradle` 在 `beforeSettings` 里把阿里云镜像插到最前，官方源保留为后备：
  ```groovy
  beforeSettings { settings ->
      settings.dependencyResolutionManagement.repositories {
          maven { url = 'https://maven.aliyun.com/repository/public' }
          maven { url = 'https://maven.aliyun.com/repository/google' }
      }
  }
  ```
  作用范围是**本机所有 Gradle 项目**，仓库里 `settings.gradle.kts` 保持干净。
- **装机包**：JDK 走清华 Adoptium 镜像、Gradle 走华为云 `mirrors.huaweicloud.com/gradle/`、cmdline-tools 走 `dl.google.com`（可直连）。
- **校验**：Gradle 官方 SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`（下载中断续传后必须重算，别信 `size_download`）。

## 版本组合与应急旋钮

主推组合（写死在 `gradle/libs.versions.toml`）：JDK 17 · Gradle Wrapper 8.13 · AGP 8.13.0 · Kotlin 2.2.20 · Compose BOM 2026.05.01 · minSdk 26 / compileSdk & targetSdk 36。

构建失败按顺序试，每次只动一处：

1. 报 Compose 编译器与 runtime 不匹配 → Kotlin 与 Compose BOM 同步升降（2.2.20 ↔ BOM 2026.05.01）。
2. 报依赖解析失败（`Could not find androidx.xxx`）→ 到 Google Maven 查该库最新稳定版替换（AndroidX 差一两个小版本不影响构建）。
3. 报 `Unsupported class file major version` → Gradle 用的 JDK 不是 17（检查 `JAVA_HOME` 或会话内 java 绝对路径）。
4. 玄学错误 → `gradlew.bat --stop` 后加 `--refresh-dependencies` 重试。
5. 依赖下载卡住 / 报连不上 `github.com` → **不要在仓库里改 `settings.gradle.kts`**，改用机器级 `C:\Users\JF\.gradle\init.d\mirrors.gradle` 注入阿里云镜像（见上文「国内网络」）。
6. `sdkmanager` 安装报 `Error on ZipFile unknown archive` → 删除 `D:\Android\sdk\.temp\` 与半装的包目录，**分包重装**（一个包的 zip 坏掉会连累同批后续包）。

## 传感器 / 服务调试

- 传感器数据实时值看 App 内「实时调试面板」（state / vib / H / n）；
- 「离线回放」：把 sensor CSV push 回 `Android/data/com.metrostop.reminder/files/logs/`，点面板里的回放按钮，与现场事件序列比对。

## 踩坑表

| 日期 | 现象 | 根因 | 解决 |
|---|---|---|---|
| 2026-09-28 | 新机上 `D:\Java` / `D:\Android` 全不存在，无 `JAVA_HOME`/`ANDROID_HOME`，`local.properties` 也没有 | **用户更换了电脑**（新机从未装过 Android 构建环境） | 按本节「环境安装流程」在新机从零装一遍；实测落点与旧机一致 |
| 2026-09-28 | `gradlew --version` 报连不上 `github.com`；`compileDebugKotlin` 报 `Could not download kotlin-compiler-embeddable-2.2.20.jar ... Connect to github.com:443 failed` | **本机到 github.com 的连接超时**；`services.gradle.org` 与 Maven Central 的该构件都会 **301 重定向到 GitHub** | JDK / Gradle 发行版从国内镜像下载（清华 Adoptium / 华为云 Gradle）；依赖走**机器级 init 脚本** `C:\Users\JF\.gradle\init.d\mirrors.gradle` 注入阿里云仓库（见「国内网络」） |
| 2026-09-28 | `sdkmanager` 安装 `platforms;android-36` 报 `Error on ZipFile unknown archive`，且**同批次后面的 platform-tools 一起没装上** | HTTP 下载的 zip 损坏；sdkmanager 整批安装时一个包失败会影响后续包 | 删掉 `D:\Android\sdk\.temp\` 与半装的 `platforms\android-36`，**分包重装**（先 platform-tools，再 platforms;android-36），第二次成功 |
| 2026-09-28 | 用 `curl -C -` 续传后 `sha256sum` 与官方不符 | 首次下载未完成（`size_download` 小于 `Content-Length`） | 续传后**必须再校验**：`sha256sum gradle-8.13-bin.zip` 应等于官方 `20f1b117...ed78` |
| 2026-09-28 | cmdline-tools 19.0 里找不到文档提到的 `android.exe` | 该新 CLI 属另一发行渠道，19.0 只有 `sdkmanager.bat` | 直接用 `sdkmanager.bat --install <包>`（等价功能） |
| 2026-09-28 | 免安装版 Gradle 解压后是 `D:\Android\gradle-8.13\gradle-8.13\bin\gradle.bat`（多一层） | zip 内顶层目录名与解压目标同名 | 解压后把内层内容上移一层，整理成 `D:\Android\gradle-8.13\bin\gradle.bat`，与文档一致 |
| 2026-09-28 | 仓库路径在两台机器上不同（旧机 `D:\Code\own-project\metro-stop`、新机 `D:\Code\home\own-project\metro-stop`） | **用户有两台开发机**，不是目录被移动 | 文档一律**不写死仓库路径**，以 `git rev-parse --show-toplevel` 为准；命令示例用 `$REPO` 占位 |
| 2026-09-25 | 本机无 `java` / `ANDROID_HOME` / `adb` | 未安装 Android 构建环境 | S1 按 `docs/README.md`「自举环境」安装 |
| 2026-09-25 | Git Bash 里 `java` / `adb` / `gradle` 找不到，PowerShell 里正常 | AI 会话环境变量在启动时快照，之后设置的读不到 | 用绝对路径调用；跑 gradlew 前先 `export JAVA_HOME='D:\Java\jdk-17.0.20.1+1'` |
| 2026-09-25 | `android.exe sdk install` 装的包落到 `C:\Users\JF\AppData\Local\Android\Sdk`，不在 `D:\Android\sdk` | 新 CLI 未读 `ANDROID_HOME`（或读的是默认位置） | 设置 `ANDROID_HOME=D:\Android\sdk` 后重装；已装的用 `mv` 移过去 |
| 2026-09-25 | `gradlew.bat assembleDebug` 报 `Licences not accepted: build-tools;35.0.0` | 1) `licenses/` 目录缺失；2) AGP 8.13 默认还要 build-tools **35**（compileSdk 36 也要装 35） | 手写 `D:\Android\sdk\licenses\android-sdk-license`（含 2 个哈希）；`android.exe sdk install "build-tools;35.0.0"` |
| 2026-09-25 | `sdkmanager --licenses` 提示 `--licenses is no longer needed` | 新 cmdline-tools 已废弃该命令 | 改用 `android.exe sdk install`；许可文件手写即可 |
| 2026-09-25 | `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` | HyperOS 默认拦截 adb 侧载（不是用户操作） | 手机 **设置 → 更多设置 → 开发者选项 → 打开「USB 安装」**（可能要登录小米账号） |
| 2026-09-25 | `adb shell input keyevent` / `pm grant` 报 `SecurityException` | HyperOS（Android 17）收紧 shell 权限：注入按键需 `INJECT_EVENTS`，改权限需 `GRANT_RUNTIME_PERMISSIONS`，shell 均无 | 屏幕相关操作（解锁 / 授权）必须**人工在手机上做**；`cmd power wakeup` 仍可用；`am start` 可用 |
| 2026-09-25 | `adb shell am start-foreground-service ... MonitorService` 报 `Requires permission not exported from uid` | 服务按安全规范声明 `android:exported="false"`，shell 无法直启 | 用 App 界面 / 磁贴 / 通知按钮触发；若要自动化调试，需临时改 exported 后重装（不推荐） |
| 2026-09-25 | 截图（`screencap`）全黑 | 设备处于锁屏息屏状态 | `adb shell cmd power wakeup` 唤醒后仍需**人工解锁**才能看到 App 界面 |
| 2026-09-25 | Git Bash 里 `adb shell ls /sdcard/...` 报 `No such file or directory`（路径被改写成 `C:/Users/.../git/sdcard/...`） | MSYS 路径转换把 `/sdcard/...` 当本地 Unix 路径 | 命令前加 `MSYS_NO_PATHCONV=1`，如 `MSYS_NO_PATHCONV=1 adb shell cat '/sdcard/.../events_x.csv'` |
| 2026-09-25 | 磁贴启动后 CSV 是 0 字节、服务秒退 | `onStartCommand` 末尾按 `isRunning` 退出；磁贴路径要异步读「上次路线」，此刻仍为 false → 服务当场 `stopSelf`。**这是修「测试提醒不退出」时引入的回归** | `ACTION_START` / `ACTION_REPLAY` 自行管理生命周期，不走该检查（见 `MonitorService.onStartCommand`） |
| 2026-09-25 | `CsvRecorder` 里 `file.bufferedWriter()` 把同步写好的表头清空 | `bufferedWriter()` 默认**截断**模式 | 改 `FileOutputStream(file, true).bufferedWriter()`（append） |
| 2026-09-25 | 真机实测：缓刹 / 制动特征被滤波抹平时**完全漏检到站** | `CRUISE` 只累计制动、不累计静止 —— 把「三条件联合判定」实现成了**门控**（必须先有 3 s 制动才看振动） | `CRUISE` 中也累计 `stillForSec`，达 `stillConfirmSec` 即到站，`note=still_no_brake` 供离线区分。回归用例：`RealCsvRegressionTest` |
| 2026-09-25 | 自动结束 / 手动结束后常驻通知又冒出来 | 1 s 的 UI ticker 与延迟回调在 teardown 之后重新 `notify(1010)` | `updateOngoing()` 首行加 `if (!isRunning) return`；teardown 主动 `cancel(ID_ONGOING)` |
| 2026-09-25 | `SENSOR_DELAY_GAME` 实供 ≈47 Hz（21 ms 间隔），非 50 Hz | 硬件/系统调度按档位取整 | 算法按**秒**累计（不按样本数），不受影响；如需严格 50 Hz 可换 `SENSOR_DELAY_FASTEST` + 软件节流 |
| 2026-09-25 | 磁贴点击后**状态不刷新**，必须收起重开通知栏（触发 `onStartListening`）才变化 | 服务是**异步**启动的（磁贴路径要先读「上次路线」），`onClick` 里立即 `render()` 时状态尚未更新；而面板展开期间没有任何监听机制 | `onStartListening` 里订阅 `SessionHolder.state.map{running}.distinctUntilChanged()`，变化即 `updateTile()`；`onStopListening` 取消订阅 |
| 2026-09-25 | 常驻通知里**看不到已运行时间** | `buildOngoing` 用了 `setShowWhen(false)` 关掉时间戳，且通知内容未含计时 | 改 `setUsesChronometer(true)` + `setWhen(当前时间 − elapsedSec)`，由系统自动走动计时，无需每秒刷新通知 |
| 2026-09-25 | 到站提醒**只弹通知不震动**；`dumpsys` 显示渠道 `mVibrationEnabled=true`、`vibrationPattern` 正确 | **HyperOS 对通知震动有额外系统级干预**，渠道震动属性实测不生效 | 改由 App 侧主动 `Vibrator.vibrate()`（`VibratorHelper`）；渠道震动关闭以避免双重震动。**渠道属性变更必须卸载重装** |
| 2026-09-25 | App 主动 `vibrate()` 仍不震；`dumpsys vibrator_manager` 显示 `ignored_for_settings \| usage: UNKNOWN` | 无 `VibrationAttributes` 的 `createWaveform()` 被系统归类为 `UNKNOWN`/`TOUCH`，而 `VibrationSettings.VibrationIntensities` 里 **`UNKNOWN = OFF`、`TOUCH = OFF`**（`ALARM`/`NOTIFICATION`/`RINGTONE` 才是 MEDIUM）→ 被直接丢弃 | 用 API 33+ 的 `vibrate(effect, VibrationAttributes.Builder().setUsage(USAGE_ALARM).build())`；API 26~32 回退 `vibrate(effect, AudioAttributes(USAGE_ALARM))`。修正后日志为 `effect \| finished \| duration: 1016ms \| usage: ALARM \| amplitude=1.00` |
| 2026-09-25 | 「车上中途开始监测」时**第一个真实到站被吞掉**（用户实测：上车站→目的站仅 1 站，却要「摇-停」两次才收到提醒） | 首站忽略规则只看 `hasRun`（是否发生过起步），未区分开始姿势。中途开始时并没有「上车站停稳」可忽略 | 新增 `TuningConfig.startMovingConfirmSec` + `StationStopDetector.startedInMotion`（预热期内振动持续 1 s → 判定「开始时列车已在行驶」）；该姿势下首次停站直接计数。`WARMUP_DONE` 的 note 记为 `started_in_motion` / `started_at_platform` 供离线分析。回归用例：`RealInMotionStartRegressionTest` |

## 命令行速查（AI 会话常用）

```bash
export JAVA_HOME='D:\Java\jdk-17.0.20.1+1'
ADB='D:\Android\sdk\platform-tools\adb.exe'
cd /d/code/own-project/metro-stop

cmd //c "gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain"   # 测试+构建
$ADB install -r app/build/outputs/apk/debug/app-debug.apk                        # 装机
$ADB logcat -c && $ADB logcat -v time -s MetroStop:*                             # 看自定义日志
$ADB shell cmd power wakeup                                                       # 唤醒屏幕（仍需人工解锁）
$ADB shell dumpsys notification --noredact | grep -A 20 metrostop                 # 查通知
$ADB shell dumpsys activity services com.metrostop.reminder                        # 查前台服务
$ADB pull /sdcard/Android/data/com.metrostop.reminder/files/logs .\logs           # 导出 CSV 三件套
```

> **无 logcat 输出属正常**：M1 未在业务代码里打日志（core 禁用 `Log`，硬性规则 1）。
> 现场诊断依赖三处：App 内「实时调试面板」、CSV 三件套、`dumpsys`。
> 若确实需要日志，请在 `platform/` 层加 `android.util.Log`（不要在 `core/` 里加）。

