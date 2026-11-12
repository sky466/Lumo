# 自研 SMB2/SMB3 客户端实现笔记

`app/src/main/java/com/letvcast/player/Smb2Client.java`，纯 Java（JDK + `javax.crypto`），
不依赖任何第三方库，能在 Android 4.3 上跑。这里记下为什么写、写了什么、踩了哪些坑。

## 为什么不用现成库

| 方案 | 问题 |
|---|---|
| jcifs 1.3.x | 只会 SMB1（CIFS）。macOS 13 起系统共享**移除**了 SMB1 服务端，Win10/11 默认关闭 SMB1 → 连不上 |
| jcifs-ng 2.1.x | 支持 SMB2/3，但用的是 `java.time` / `StandardCharsets` 等 API 19+ 的类，Android 4.3(API18) 上没有 |
| smbj | 依赖 BouncyCastle，且大量 Java 8 / NIO 用法，老 Android 上加载即崩 |

结论：要同时满足「SMB2/3」+「Android 4.3」，自己实现是最省事的路。

## 协议范围

只实现「读」这一个方向，够播放器用：

```
TCP:445
  NEGOTIATE           方言协商（2.0.2 / 2.1 / 3.0 / 3.0.2）
  SESSION_SETUP x2    NTLMv2 认证（Type1 / Type3）
  TREE_CONNECT        挂载 \\host\share
  CREATE              打开文件或目录，顺便拿到文件大小
  QUERY_DIRECTORY     列目录（FileIdBothDirectoryInformation）
  READ                按偏移读（就是播放器的 seek）
  CLOSE
```

**不做**：加密、DFS、多通道、压缩、Kerberos、写操作。
理由：局域网放个电影用不到，而且每多一项就多一份在老固件上出错的可能。

不声明 `SMB2_GLOBAL_CAP_ENCRYPTION`，所以服务器不会对这条连接加密。

## 报文结构（偏移都从 64 字节头之后算起）

头部 64 字节，关键的几个位置：

| 偏移 | 字段 |
|---|---|
| 0–3 | `FE 53 4D 42`（0xFE + "SMB"） |
| 4–5 | StructureSize = 64 |
| 12–13 | Command |
| 16–19 | Flags（`0x1` 是响应，`0x8` 是已签名） |
| 24–31 | MessageId |
| 36–39 | TreeId |
| 40–47 | SessionId |
| 48–63 | Signature |

实现里最容易写错的几处（都是实际调试时踩的）：

1. **CREATE 请求的定长部分是 56 字节**，`NameOffset = 64 + 56 = 120`；
   但 `StructureSize` 写 57（含 1 字节 buffer）。
2. **FileIdBothDirectoryInformation（0x25）的文件名在偏移 104**，不是 112。
   结构里 `ShortName[24]` 后面只有 2 字节 Reserved，然后才是 8 字节 FileId。
   写错 8 个字节的表现是：文件名前面多几个空格、后面的项整体错位。
3. **NEGOTIATE / SESSION_SETUP 响应里的 offset 是「相对 SMB2 头起点」**，
   不是相对 body，所以算数组下标要减 64。
4. **READ 响应的 DataOffset 只有 1 个字节**（位于 body+2），DataLength 在 body+4。

## 认证：NTLMv2

```
NTOWFv2   = HMAC_MD5(MD4(UTF16LE(password)), UTF16LE(Upper(user) + domain))
blob      = 01 01 0000 | 0000 | timestamp(8) | clientChallenge(8) | 0000 | targetInfo | 0000
NTProof   = HMAC_MD5(NTOWFv2, serverChallenge || blob)
NTResp    = NTProof || blob
LMv2      = HMAC_MD5(NTOWFv2, serverChallenge || clientChallenge) || clientChallenge
SessionBaseKey = HMAC_MD5(NTOWFv2, NTProof)
```

`targetInfo` 原样从服务器的 Type2 挑战里抄回来（里面有时间戳、目标名等信息）。

`javax.crypto` 没有 MD4，所以自己写了 MD4（`md4()`），并用官方测试向量校验：
`MD4("")`、`MD4("abc")`、`MD4("message digest")`。

服务器若在 `MsvAvFlags` 里声明要求 MIC（`0x2`），会把
`MIC = HMAC_MD5(ExportedSessionKey, Type1 || Type2 || Type3[mic=0])` 补进 Type3。

## SPNEGO 包装

认证消息必须包在 GSS-API SPNEGO 里，DER 长度一个字节都不能错：

```
60 LL                          # InitialContextToken
  06 06 2b 06 01 05 05 02      # SPNEGO OID
  a0 LL                        # NegTokenInit
    30 LL
      a0 LL 30 LL              # mechTypes
        06 0a 2b 06 01 04 01 82 37 02 02 0a   # NTLMSSP OID（10 字节）
      a2 LL 04 LL <NTLMSSP Type1>
```

**坑**：手写死长度时把 NTLMSSP 的 OID 当成 15 字节，SEQUENCE 长度就多算了 5 个字节，
服务器解析失败的表现不是「格式错误」，而是直接断开连接 / 认证失败。
现在改成用 `derWrap(tag, content)` 逐层拼，长度自动算。

SMB2 头部里还有 `NextCommand` 字段支持复合请求（compounding），这里没用，
一次只发一个请求，所以逻辑简单很多。

## 签名

| 方言 | 算法 | 密钥 |
|---|---|---|
| SMB 2.0.2 / 2.1 | HMAC-SHA256 | SessionKey 本身 |
| SMB 3.0 / 3.0.2 | AES-128-CMAC | `SigningKey = KDF(SessionKey, "SMB2AESCMAC", "SmbSign")` |

签名规则：把头部 48–63 字节清零，对整个 PDU 计算 MAC，写回前 16 字节，
同时把 Flags 的 `0x8` 位置 1（这一步必须在算 MAC 之前）。

KDF 是 NIST SP800-108 Counter Mode（HMAC-SHA256）：

```
K(1) = HMAC-SHA256(SessionKey, 00000001 || Label || 00 || Context || 00 || 00000080)
```

`javax.crypto` 也没有 CMAC，所以按 RFC 4493 手写了 `aesCmac()`
（子密钥 = AES-128-ECB 加密全零块后按 GF(2^128) 左移，末块不满则补 0x80 再异或 K2）。
用 RFC 4493 的三组测试向量验证过（空串、16 字节、40 字节）。

如果 SMB3 的签名在某些服务器上被拒，`SmbClient` 会自动用 SMB2.1 重新连接一次
（见 `SmbClient.smb2()`），这样即使某个算法边角不对，用户也只是「协议版本低一点」而不是连不上。

## 会话密钥交换

服务器 Type2 里如果带 `NEGOTIATE_KEY_EXCH`，会话密钥是随机生成、用
`RC4(KeyExchangeKey, RandomSessionKey)` 加密后传给服务器的（RC4 也是手写的，10 行）。
没有这个标志时直接用 `SessionBaseKey`。

## 怎么测

仓库里没有内置测试服务器，可以用 impacket 起一个只支持 SMB2 的验证环境：

```bash
python3 -m venv /tmp/smbtest && /tmp/smbtest/bin/pip install impacket
mkdir -p /tmp/smbshare/videos && echo hello > /tmp/smbshare/videos/hello.txt
/tmp/smbtest/bin/smbserver.py -smb2support -username test -password test \
        -port 1445 -debug SHARE /tmp/smbshare
```

然后用 App 的「＋」添加 `127.0.0.1:1445`（手机）/ `10.0.2.2:1445`（模拟器），共享名 `SHARE`，
账号 `test` / `test`。

校验点：

- 连接日志里出现 `connected ... via SMB 2.0.2`（或 2.1 / 3.0.2）；
- 目录能列出来（含中文名）；
- 拖动进度条能跳转（Range → READ 偏移正确）；
- 退出播放器不卡（`release done` 应在 100ms 内出现）。

## 已知限制

- 不支持 Kerberos：纯 AD 域环境里如果禁用了 NTLM，会连不上。
- 不支持 SMB3 加密共享（服务器要求加密时会返回 ACCESS_DENIED）。
- 不支持 DFS 引用跳转：`\server\dfsroot` 这种会挂载失败。
- 单连接串行请求：播放时同一个会话上的列目录请求会排队，实测影响可忽略（都是毫秒级）。
