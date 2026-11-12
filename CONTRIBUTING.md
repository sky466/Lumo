# 参与开发

这个项目面向的是「老 Android 电视」这种很具体的环境，所以改动请优先考虑两件事：
**低版本兼容性**（Android 4.3 / API 18 上必须能跑）和 **遥控器可用性**（方向键能不能走位到）。

## 提 issue 时请附上

1. 设备型号 + Android 版本（设置 → 诊断 第一行就有：`app=1.33(133) android=4.3(API 18)`）；
2. 设置 → 诊断 里的整段日志（或 `adb logcat -s LogBus:V`）；
3. 复现步骤：从哪个界面、点了什么、期望结果与实际结果。

## 改代码时请注意

- **不要在主线程做网络 IO**：SMB / WebDAV 全部走后台线程，播放器退出时也不许等网络回包，
  关 socket 就走（否则老系统的 `MediaPlayer.release()` 会卡住输入事件，直接 ANR）。
- **不要用 API 19+ 才有的类**（`java.time`、`java.nio.file`、`java.util.Base64`、
  `StandardCharsets` 等）；AndroidX 的 `androidx.annotation` 无所谓，但别引入重依赖。
- **播放时不要弹 Toast / Dialog**，反馈统一用 `showHud()`。
- 新增 SMB 相关代码请同时考虑 SMB2（默认）与 SMB1（兜底）两条路径，
  两者共用 `SmbHandle` 接口。
- 布局改 TV（`res/layout-w720dp/`）时，注意 `w720dp` 用的是「最小宽度」而不是屏幕宽度，
  X50 Air 是 `540dp` 宽、`sw` 只有 `540dp`，所以不能用 `sw720dp` 限定符。

## 提交

- 一个 commit 做一件事，message 用一句话说清「改了什么现象」；
- 涉及播放引擎 / SMB 协议的改动，请在 PR 描述里写清怎么验证的（哪个设备、哪个片源、
  哪个服务端）。
