# 方易通语音助手第三方音乐适配模块（LSPosed）

为**方易通车机语音助手"车助理"**（包名 `com.syu.voice`，APK：车助理设置_1.0.apk）
注入 **QQ 音乐车机版 / QQ 音乐 / 网易云音乐车机版 / 网易云音乐** 等第三方播放器支持，
使语音指令（播放/暂停/切歌/点歌）可以控制这些播放器。

GitHub Actions 自动构建并发布 Release，模块内置**在线更新**与**运行日志**。

## 支持列表（v1.1.0）

| 包名 | 播放器 |
|---|---|
| `com.tencent.qqmusiccar` | QQ音乐车机版 |
| `com.tencent.qqmusic` | QQ音乐（手机版/HD） |
| `com.netease.cloudmusic.iot` | 网易云音乐车机版 |
| `com.netease.cloudmusic` | 网易云音乐 |

> 控制通道统一为 MediaSession：以上播放器均已实现标准 MediaSession，
> 车助理为 system uid，可直接接管控制。

## 原理（反编译结论）

车助理基于腾讯 TXZ 语音 SDK（宿主 `com.txznet.txz` = TXZ_2.9.8.apk）。
语音指令流转：

```
语音 → TXZ宿主(com.txznet.txz) → com.syu.voice.VoiceAdapter
     → NaviTools.setMusicTool(包名) → TXZMusicManager.setMusicTool(MusicTool实现)
     → 宿主将播放/暂停/切歌/点歌指令转发回 MusicTool 实现 → 播放器
```

关键类（车助理设置_1.0.apk）：

| 类 | 作用 |
|---|---|
| `VoiceAdapter$NaviTools` | 音乐工具白名单 + 注册入口 |
| `NaviTools.mSupportMusicTools` | 受支持播放器包名白名单 |
| `NaviTools.checkMusicTools()` | 扫描已安装应用填充"音乐工具选择"列表 |
| `NaviTools.supportMusicTool(pkg)` | 包名是否受支持 |
| `NaviTools.setMusicTool(pkg)` | 按包名注册 MusicTool 实现 |
| `com.txznet.sdk.TXZMusicManager` | 音乐控制管理器 |
| `TXZMusicManager$MusicTool` | 音乐工具接口（18 个方法） |

**原白名单没有 QQ 音乐 / 网易云**：`cn.kuwo.player`、`cn.kuwo.kwmusiccar`、
`com.txznet.music`、`com.syu.music`、`com.tencent.wecarflow`、`com.kugou.android.auto`。

模块做法：
1. 把目标包名补进白名单 → 设置界面自动出现选项；
2. Hook `setMusicTool`：目标包名 → 注册一个**基于 MediaSession 的动态代理 MusicTool**；
3. 代理通过 `MediaSessionManager.getActiveSessions()` 找到播放器的 `MediaController`，
   用 `TransportControls` 执行 play / pause / next / prev / playFromSearch（语音点歌）。

## 功能

- **语音控制**：播放 / 暂停 / 继续 / 上一首 / 下一首 / 切歌 / 语音点歌（playFromSearch）
- **运行日志**：Logcat + 文件双写
  - 日志文件：`/sdcard/QQMusicVoiceInject/logs/QQMusicVoiceInject.log`（1MB 自动轮转，保留 3 份）
  - 查看方式：打开模块 App →「查看运行日志」；或 adb：`adb logcat -s QQMusicVoice`
- **在线更新**：模块 App →「检查更新」→「下载并安装」
  - 数据源：`https://api.github.com/repos/District1655/fytMusicVoiceInject/releases/latest`
  - 版本规则：tag `vX.Y.Z` ↔ versionCode `主*10000+次*100+补丁`
- **网易云车机版**：加入白名单，MediaSession 控制

## 工程结构

```
QQMusicVoiceInject/
├── .github/workflows/build.yml   # GitHub Actions 自动构建 + Release
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle              # compileOnly xposed-api:82；versionCode 10100
    └── src/main/
        ├── AndroidManifest.xml   # LSPosed 声明 + MainActivity + 权限
        ├── assets/xposed_init    # 入口类
        └── java/com/syu/voice/hook/
            ├── MainHook.java         # Xposed 入口（日志初始化 + hook）
            ├── MusicToolInject.java  # 4 个 Hook 点 + 白名单注入
            ├── QQMusicToolProxy.java # MediaSession 动态代理 MusicTool
            ├── LogManager.java       # Logcat + 文件日志
            ├── UpdateManager.java    # GitHub Releases 检查/下载/安装
            └── MainActivity.java     # 模块 UI（日志/更新）
```

## 构建

### 方式一：GitHub Actions（推荐，自动）
推送 `main` 分支即自动构建并发布 Release（含 APK），模块内"检查更新"即可在线升级。

### 方式二：本地 Android Studio
1. 打开本工程，等待 Gradle 同步（已配阿里云镜像）；
2. Build → Build APK(s)，产物 `app/build/outputs/apk/debug/app-debug.apk`。

## 部署（车机，LSPosed 环境）

1. 车机已 root 并安装 **LSPosed** 与 **Magisk**（或 KSU）；
2. 安装模块 APK（来自 Release 或本地构建）；
3. LSPosed → 模块 → 勾选「方易通语音助手第三方音乐适配模块」→ 作用域勾选 **车助理**（com.syu.voice）；
4. **重启车机**；
5. 打开"车助理设置 → 音乐工具选择"→ 选择 **QQ音乐车机版 / 网易云音乐车机版**；
6. 打开对应播放器开始播放，测试「播放 / 暂停 / 下一首 / 播放《XXX》」。

## 常见问题

| 现象 | 处理 |
|---|---|
| 设置里没有目标播放器选项 | 确认已安装对应包名且重启过；检查 LSPosed 作用域勾选 |
| 能播放但"点歌"没反应 | 部分车机版对 `playFromSearch` 支持有限，需播放器内先登录、搜索服务正常 |
| 收藏/播放模式无效果 | 目标车机版无公开收藏/循环模式 AIDL，接口为空实现（语音会跳过） |
| 想加其他播放器 | 编辑 `MusicToolInject.TARGET_MUSIC_PKGS` 加包名重新构建（需实现 MediaSession） |
| 排查问题 | 打开模块 App →「查看运行日志」；或 `adb logcat -s QQMusicVoice` |
| 检查更新失败 | 车机需能访问 `api.github.com`；仓库需已有 Release（首次 push 后自动发布） |

## 安全与合规

- 本模块仅作技术学习与自有设备功能增强，请勿用于商业分发或绕过版权保护；
- 修改系统应用（com.syu.voice 为 system uid）存在刷机风险，操作前请备份；
- 语音点歌依赖播放器的在线搜索服务，受其会员/版权策略影响。


