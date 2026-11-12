# 第三方组件与许可证

Lumo 自己的代码是 MIT。下面这些是别人做的轮子，各自沿用原许可证，
打包进 APK 时请注意：**LGPL 组件要求「允许用户替换该库」**。
自用没问题；如果要上架应用商店或分发修改版，请先读一遍各自的许可证。

| 组件 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| [ijkplayer](https://github.com/bilibili/ijkplayer) | 0.8.8 | 播放内核（FFmpeg 软解 + MediaCodec 硬解） | LGPL-2.1+ |
| FFmpeg（随 ijkplayer 打包） | 3.x | 解封装 / 解码 | LGPL-2.1+（未启用 GPL 组件） |
| [jcifs](https://jcifs.samba.org/) | 1.3.17 | SMB1 / CIFS 客户端（仅老设备兜底） | LGPL-2.1+ |
| [AndroidX / Support Library](https://developer.android.com/jetpack) | 由 ijkplayer 传递依赖 | 兼容库 | Apache-2.0 |

## 本项目自研、无第三方依赖的部分

- `Smb2Client.java` —— SMB 2.0.2 / 2.1 / 3.0 / 3.0.2 客户端，
  含 NTLMv2 认证、MD4、RC4、HMAC-MD5/SHA-256、AES-CMAC（RFC 4493）、SP800-108 KDF，
  全部为标准算法的手写实现，只用 JDK 自带的 `javax.crypto`。
- `WebDavClient.java` / `WebDavStreamProxy.java` —— WebDAV 客户端与流代理。
- `UsbStorage.java` / `LocalClient.java` —— 老系统上的外接存储识别。
- 全部 UI 与播放器控制代码。

## 图标

Launcher 图标为项目自带素材；如需替换，直接覆盖 `app/src/main/res/mipmap-*/ic_launcher.png`。
