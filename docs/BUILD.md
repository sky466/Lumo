# 构建与签名

## 环境

| 工具 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | AGP 8.2 要求 17+ |
| Android SDK | compileSdk 34 / build-tools 34.0.0 | `sdk.dir` 写进 `local.properties` |
| Gradle | 8.6 | 用仓库自带的 `./gradlew` 即可，不用另装 |
| AGP | 8.2.1 | 由 `build.gradle` 里的 classpath 指定 |

`local.properties`：

```properties
# macOS
sdk.dir=/Users/你的名字/Library/Android/sdk
# Linux
# sdk.dir=/home/你的名字/Android/Sdk
# Windows
# sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk
```

## 出正式包

```bash
# 1) 第一次需要自己的签名（仓库里没有、也不该有别人的签名文件）
keytool -genkeypair -v -keystore letvcast.keystore -alias letvcast \
        -keyalg RSA -keysize 2048 -validity 10000

# 2) 告诉 Gradle 密码（这个文件已被 .gitignore 排除）
cp keystore.properties.example keystore.properties
$EDITOR keystore.properties

# 3) 编译
./gradlew clean assembleRelease

# 产物
ls -lh app/build/outputs/apk/release/app-release.apk
```

`app/build.gradle` 会在 `keystore.properties` 存在时自动签名；不存在时
只产出未签名包（CI 里够用）。

## 手动签名（不配 keystore.properties 时）

Android 4.3 的设备对 v2/v3 签名不敏感，用 v1+v2 全开最稳：

```bash
BT=$HOME/Library/Android/sdk/build-tools/34.0.0

$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign \
    --ks letvcast.keystore --ks-key-alias letvcast \
    --ks-pass pass:你的密码 --key-pass pass:你的密码 \
    --out Lumo-v1.33.apk aligned.apk
$BT/apksigner verify --print-certs Lumo-v1.33.apk
```

> 4KB 对齐（`-p 4`）对老设备有用：某些固件的 `mmap` 只认 4K 对齐，
> 否则 `.so` 加载会失败。

## 装到电视上

```bash
adb connect 192.168.1.20:5555      # 电视开了 adb 调试
adb install -r Lumo-v1.33.apk
adb shell am start -n com.letvcast.player/.MainActivity

# 看日志（不用连 adb 的话，设置 → 诊断 里也能看）
adb logcat -s LogBus:V
```

## 在 arm64 手机上调试

仓库只打包 `armeabi-v7a`。arm64 设备要跑，把 `app/build.gradle` 的
`abiFilters` 改成 `arm64-v8a` 再编译——此时 ijkplayer 的 `.so` 不在包里，
ijk 系引擎会报 `UnsatisfiedLinkError`，把引擎切到「系统解码」即可。

## 崩溃日志在哪

- Java 崩溃：应用私有目录 `files/crash.log`
- native 信号（SIGSEGV/SIGBUS…）：`files/native_crash.log`，由 `cpp/crashcatch.c` 写入
- 每次运行的行为日志：`files/session.log`

电视上不方便取文件时：设置 → 诊断，整段内容可直接截图或复制。
