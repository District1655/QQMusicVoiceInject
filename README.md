# fytMusicVoiceInject（方易通语音助手第三方音乐适配模块，LSPosed）

为**方易通车机语音助手"车助理"**（包名 `com.syu.voice`，APK：车助理设置_1.0.apk）
注入 **QQ 音乐车机版 / QQ 音乐HD / QQ 音乐 / 网易云音乐车机版 / 网易云音乐** 等第三方播放器支持，
使语音指令（播放/暂停/切歌/点歌/后台搜索直接播放）可以控制这些播放器。

GitHub Actions 自动构建并发布 Release，模块内置**在线更新**与**运行日志**。

## 支持列表（v1.3.2）

| 包名 | 播放器 | 说明 |
|---|---|---|
| `com.tencent.qqmusiccar` | QQ音乐车机版 | 官方 AIDL `voicePlay` 后台搜索直接播放 |
| `com.tencent.qqmusicpad` | QQ音乐HD / Pad版 | 同上（车机实测主力） |
| `com.tencent.qqmusic` | QQ音乐（手机版） | AIDL + 广播兼容 |
| `com.netease.cloudmusic.iot` | 网易云音乐车机版 | 白名单注入 |
| `com.netease.cloudmusic` | 网易云音乐 | 白名单注入 |

## 原理（反编译结论）

车助理基于腾讯 TXZ 语音 SDK（宿主 `com.txznet.txz` = TXZ_2.9.8.apk）。
语音指令流转：

```
语音 → TXZ宿主(com.txznet.txz) → com.syu.voice.VoiceAdapter
     → NaviTools.setMusicTool(包名) → TXZMusicManager.setMusicTool(MusicTool实现)
     → 宿主将播放/暂停/切歌/点歌指令转发回 MusicTool 实现 → 播放器
```

模块为三进程注入架构（LSPosed 作用域）：

| 进程 | 注入内容 |
|---|---|
| `com.syu.voice`（车助理） | 音乐工具白名单 + `MusicTool` 动态代理（把指令转发给 QQ音乐进程） |
| `com.tencent.qqmusiccar/pad/qq`（QQ音乐） | 进程内 hook QQ音乐官方 AIDL 实现 `ApiMethodsImpl`：`voicePlay()` 后台搜索直接播放、`skipToNext/skipToPrevious/pauseMusic` 等播放控制；hook `QQMusicServiceProxyHelper.m()` 绕过 PlayerService 前置检查（v1.3.1）；实例未就绪时命令缓存补发 |
| `com.txznet.txz`（TXZ 语音主服务） | hook 音乐模块 `y()`，强制"上一曲/下一曲/暂停"走 MusicTool 链路而不是系统媒体键（v1.3.2） |

关键类（反编译自 车助理设置_1.0.apk / QQ音乐HD / TXZ_2.9.8.apk）：

| 类 | 作用 |
|---|---|
| `VoiceAdapter$NaviTools` | 音乐工具白名单 + 注册入口 |
| `TXZMusicManager$MusicTool` | 音乐工具接口（18 个方法） |
| `com.txznet.txz.module.music.b` | TXZ 音乐模块，分发 next/prev/pause/play 命令 |
| `com.tencent.qqmusiccar.third.api.apiImpl.ApiMethodsImpl` | QQ音乐官方第三方 AIDL 实现（voicePlay 等） |
| `QQMusicServiceProxyHelper.m()` | PlayerService 绑定检查（播放控制的前置拦截点） |

## 功能

- **语音点歌**：播放《XXX》/ 播放某歌手的歌 → QQ音乐 **后台搜索直接播放**（不弹搜索框）
- **语音控制**：播放 / 暂停 / 继续 / 上一首 / 下一首 / 切歌（v1.3.1+ 绕过 PlayerService 检查）
- **自动拉起**：QQ音乐未运行时先启动再操作（v1.3.1）
- **播放状态上报**：isPlaying 保底 true + onStatusChange 主动上报，维持 TXZ 音乐场景（v1.3.2）
- **运行日志**：Logcat + 文件双写，各进程独立目录
  - 车助理进程：`/sdcard/Android/data/com.syu.voice.hook/files/logs/fytMusicVoiceInject.log`
  - QQ音乐进程：`/sdcard/Android/data/<对应包名>/files/logs/fytMusicVoiceInject.log`
  - 模块 App「查看运行日志」自动合并全部进程日志（1MB 轮转，保留 3 份）
  - adb：`adb logcat -s fytMusicVoice`
- **在线更新**：模块 App →「检查更新」→「下载并安装」
  - 数据源：`https://api.github.com/repos/District1655/fytMusicVoiceInject/releases/latest`
  - 版本规则：tag `vX.Y.Z` ↔ versionCode `主*10000+次*100+补丁`
- **重启车机**：模块 App 内置"重启车机"按钮（su + svc power reboot）

## 工程结构

```
fytMusicVoiceInject/
├── .github/workflows/build.yml   # GitHub Actions 自动构建 + Release（仅 app/ 与 workflow 变更触发）
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle              # compileOnly xposed-api:82；versionCode 10302
    └── src/main/
        ├── AndroidManifest.xml   # LSPosed 声明（作用域含 com.txznet.txz）+ MainActivity
        ├── assets/xposed_init    # 入口类
        └── java/com/syu/voice/hook/
            ├── MainHook.java         # Xposed 入口（进程分流）
            ├── TXZHook.java          # TXZ 主服务 hook（v1.3.2）
            ├── QQProcessHook.java    # QQ音乐进程 hook（AIDL + 缓存补发）
            ├── ApiHolder.java        # ApiMethodsImpl 实例持有 + voicePlay/控制
            ├── MusicToolInject.java  # 白名单注入（5 个目标包名）
            ├── QQMusicToolProxy.java # MusicTool 动态代理（转发 + 状态上报）
            ├── QQMusicController.java# 控制广播 + 进程拉起（ensureRunning）
            ├── LogManager.java       # Logcat + 文件日志（多进程独立目录）
            ├── ContextHolder.java    # Application Context 持有
            ├── UpdateManager.java    # GitHub Releases 检查/下载/安装
            └── MainActivity.java     # 模块 UI（日志/更新/重启/日志开关）
```

## 构建

### 方式一：GitHub Actions（推荐，自动）
推送 `main` 分支（app/ 或 workflow 变更）即自动构建并发布 Release（含 APK），
模块内"检查更新"即可在线升级。构建使用固定 debug keystore（Secret `DEBUG_KEYSTORE_BASE64`），
与本地构建签名一致，可覆盖安装。

### 方式二：本地构建
```powershell
$env:JAVA_HOME = "<JDK17路径>"
& "<gradle-8.5路径>\bin\gradle.bat" :app:assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 部署（车机，LSPosed 环境）

1. 车机已 root 并安装 **LSPosed** 与 **Magisk**（或 KSU）；
2. 安装模块 APK（来自 Release 或本地构建）；
3. **LSPosed → 模块 → 勾选「fytMusicVoiceInject」→ 作用域勾选以下全部**：
   - **车助理**（`com.syu.voice`）——必选；
   - **QQ音乐车机版**（`com.tencent.qqmusiccar`）、**QQ音乐HD**（`com.tencent.qqmusicpad`）、**QQ音乐**（`com.tencent.qqmusic`）——按实际安装勾选；
   - **TXZ 语音主服务**（`com.txznet.txz`）——v1.3.2+ 必选（播放控制命令路由的关键）；
4. **重启车机**；
5. 打开"车助理设置 → 音乐工具选择"→ 选择 **QQ音乐HD / QQ音乐车机版**（已勾选且安装后自动出现在列表）；
6. 打开对应播放器，测试：
   - 「播放《周杰伦的晴天》」→ QQ音乐**后台直接播放**（不弹搜索框）；
   - 「下一曲 / 上一曲 / 暂停 / 播放」→ 立即生效；
   - QQ音乐未运行时说「播放《XXX》」→ 自动启动后播放。

> 若修改作用域或更新模块，必须**重启车机**（LSPosed 按作用域注入，QQ音乐进程需冷启动才生效）。

## 常见问题

| 现象 | 处理 |
|---|---|
| 设置里没有目标播放器选项 | 确认已安装对应包名、作用域已勾选、已重启车机 |
| 播放控制（下一曲/暂停）没反应 | 确认作用域勾选了 `com.txznet.txz`（TXZ 语音）并重启；QQ音乐进程日志看是否走到 AIDL 控制 |
| 点歌后弹搜索框 | 旧版本行为；v1.3.0+ 已改后台 `voicePlay` 直接播放，确认安装最新版 |
| 搜出歌曲但不自动播放 | 检查 QQ音乐内登录状态与网络；日志确认 `voicePlay` 回调 |
| 收藏/播放模式无效果 | 目标车机版无公开收藏/循环模式 AIDL，接口为空实现 |
| 想加其他播放器 | 编辑 `MusicToolInject.TARGET_MUSIC_PKGS` 加包名重新构建 |
| 排查问题 | 打开模块 App →「查看运行日志」（自动合并所有进程日志）；或 `adb logcat -s fytMusicVoice` |
| 检查更新失败 | 车机需能访问 `api.github.com`；仓库需已有 Release |

## 版本历史

| 版本 | 内容 |
|---|---|
| v1.3.2 | TXZ 主服务 hook y() 强制控制走 MusicTool；isPlaying 保底 true 维持音乐场景；播放状态主动上报 |
| v1.3.1 | 绕过 PlayerService 检查修复播放控制无反应；QQ音乐未运行先启动；命令缓存补发；多进程日志合并 |
| v1.3.0 | QQ音乐官方 AIDL voicePlay 后台搜索直接播放（不弹搜索框） |
| v1.2.x | 网易云白名单、运行日志、在线更新、重启车机、日志开关、固定签名 |
| v1.1.0 | 初版：白名单注入 + 动态代理 |

## 安全与合规

- 本模块仅作技术学习与自有设备功能增强，请勿用于商业分发或绕过版权保护；
- 修改系统应用（com.syu.voice 为 system uid）存在刷机风险，操作前请备份；
- 语音点歌依赖播放器的在线搜索服务，受其会员/版权策略影响。
