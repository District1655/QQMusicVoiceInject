# fytMusicVoiceInject（方易通语音助手第三方音乐适配模块，LSPosed）

为**方易通车机语音助手"车助理"**（包名 `com.syu.voice`，APK：车助理设置_1.0.apk）
注入 **QQ 音乐车机版 / QQ 音乐HD / QQ 音乐 / 网易云音乐车机版 / 网易云音乐** 等第三方播放器支持，
使语音指令（播放/暂停/切歌/点歌/后台搜索直接播放）可以控制这些播放器。

GitHub Actions 自动构建并发布 Release，模块内置**在线更新**与**运行日志**。

本项目以 **MIT 许可证** 开源（见 LICENSE），仅供个人学习研究使用。

> **⚠ 回退说明（2026-09-08）**：v1.3.4~v1.4.7 的 search 点歌链路（search+playSongMidAtIndex）
> 在车机上实测不稳定（QQ 的 search 走网络 OpenApiSDK，无网/慢网超时 15s；整体点歌表现劣于
> v1.3.3），**项目已整体回退**（点歌主路径 = QQ 官方 AIDL voicePlay + 识别纠错层 + 语义槽）。
> 1.3.4~1.4.7 全部废弃，本地 release 与 GitHub 历史均已删除。
> v1.5.0 = v1.3.3 主路径 + 定向移植回 v1.4.4 的语义槽修复（KNOWN_SINGERS + buildSlots，
> 解决回退后"播放毛不易的歌"被 QQ NLU 猜错播错歌的问题）。
> v1.6.x = 日志能力补齐（一键导出 + 结果三态弹窗）；
> v1.6.2 定位并修复"毛不易播错"真正根因：点歌广播 search_key 的 Base64 未 URL 编码，
> `+` 被解析为空格导致解码乱码（v1.5.0 语义槽修复因此未生效，voicePlay 收到的已是乱码）。

## 支持列表（v1.6.3）

| 包名 | 播放器 | 说明 |
|---|---|---|
| `com.tencent.qqmusiccar` | QQ音乐车机版 | 官方 AIDL `voicePlay` 后台搜索直接播放 |
| `com.tencent.qqmusicpad` | QQ音乐HD / Pad版 | 同上（车机实测主力） |
| `com.tencent.qqmusic` | QQ音乐（手机版） | AIDL + 广播兼容 |
| `com.netease.cloudmusic.iot` | 网易云音乐车机版 | 白名单注入 |
| `com.netease.cloudmusic` | 网易云音乐 | 白名单注入 |

## 工作原理（接口适配说明）

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
| `com.tencent.qqmusiccar/pad/qq`（QQ音乐） | 适配其第三方 AIDL 接口：`voicePlay()` 后台搜索直接播放、`skipToNext/skipToPrevious/pauseMusic` 等播放控制；兼容服务绑定状态，实例未就绪时命令缓存补发 |
| `com.txznet.txz`（TXZ 语音主服务） | 适配 TXZ 语音音乐命令路由，使"上一曲/下一曲/暂停"走 MusicTool 适配链路而不是系统媒体键（v1.3.2） |

关键类（接口来源：厂商开放的第三方 AIDL / SDK 集成接口）：

| 类 | 作用 |
|---|---|
| `VoiceAdapter$NaviTools` | 音乐工具白名单 + 注册入口 |
| `TXZMusicManager$MusicTool` | 音乐工具接口（18 个方法） |
| `com.txznet.txz.module.music.b` | TXZ 音乐模块，分发 next/prev/pause/play 命令 |
| `ApiMethodsImpl` | QQ音乐官方第三方 AIDL 实现（voicePlay 等） |
| `QQMusicServiceProxyHelper.m()` | QQ音乐第三方服务绑定状态（播放控制的衔接点） |

## 功能

- **语音点歌**：播放《XXX》/ 播放某歌手的歌 → QQ音乐 **后台搜索直接播放**（不弹搜索框）
- **语音控制**：播放 / 暂停 / 继续 / 上一首 / 下一首 / 切歌（v1.3.1+ 适配 PlayerService 绑定状态）
- **自动拉起**：QQ音乐未运行时先启动再操作（v1.3.1）
- **播放状态上报**：isPlaying 保底 true + onStatusChange 主动上报，维持 TXZ 音乐场景（v1.3.2）
- **点歌识别纠错**：语音识别的歌手/歌名同音错字自动纠正后再点歌（v1.3.3，SongCorrector 内置字典可扩充）
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
    ├── build.gradle              # compileOnly xposed-api:82；versionCode 10603
    └── src/main/
        ├── AndroidManifest.xml   # LSPosed 声明（作用域含 com.txznet.txz）+ MainActivity
        ├── assets/xposed_init    # 入口类
        └── java/com/syu/voice/hook/   # v1.6.3
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
| v1.6.3 | 可观测性修复：logcat 导出 5000→30 万行（车机日志 10 秒滚完导致拦截证据丢失）；播放器进程文件日志不落盘修复（Application hook 无 try/catch 致 init 不执行）；解码端自愈（旧广播空格还原 '+'）；导出补 /data/media/0 等候选路径（当前版本） |
| v1.6.2 | 修复点歌 Base64 未 URL 编码：关键词 Base64 含 `+`/`/` 时（如"毛不易"→`5q+b5LiN5piT`）`+` 被解析为空格导致解码乱码、播错歌；周杰伦等不含特殊字符的恰好正常 |
| v1.6.1 | 修复导出日志失败/残缺无法识别：结果三态化弹窗（✅成功/⚠️不完整缺root/❌失败）+ Magisk 授权指引，无 root 不再"假成功" |
| v1.6.0 | 日志一键导出：zip 打包全部作用域进程日志（含轮转）+ logcat + info.txt 到 Download；读取/清理 root 兜底，修复 Android 10 分区存储下看不到 QQ音乐HD 等进程日志 |
| v1.5.0 | 定向移植回 v1.4.4 语义槽修复（KNOWN_SINGERS + buildSlots），voicePlay 携带 Singer/Track 槽，修复回退后"毛不易"等歌手点歌被 QQ NLU 猜错 |
| v1.3.3 | 点歌识别纠错层 SongCorrector——歌手/歌名同音错字自动纠正（毛不易/像我这样的人等内置字典），extractQuery 与 voicePlay 双入口，命中记日志便于扩充 |
| v1.3.2 | TXZ 主服务命令路由适配；isPlaying 保底 true 维持音乐场景；播放状态主动上报 |
| v1.3.1 | 适配 PlayerService 绑定状态修复播放控制无反应；QQ音乐未运行先启动；命令缓存补发；多进程日志合并 |
| v1.3.0 | QQ音乐官方 AIDL voicePlay 后台搜索直接播放（不弹搜索框） |
| v1.2.x | 网易云白名单、运行日志、在线更新、重启车机、日志开关、固定签名 |
| v1.1.0 | 初版：白名单注入 + 动态代理 |

## 安全与合规

- 本模块为**个人学习研究项目**，仅用于**自有设备**的功能增强，**免费开源、不提供任何商业服务**；
- **与腾讯、方易通等厂商无任何关联**，项目名称及文档中提及的商标、产品名称仅用于客观描述适配对象，未使用其任何商标标识、图标或素材；
- 请支持正版：语音点歌依赖播放器的在线搜索服务，受其会员/版权策略影响；
- 请勿将本模块用于商业分发、破解付费功能或绕过版权保护；下载体验后请于 **24 小时内删除**；
- 修改系统应用（com.syu.voice 为 system uid）存在刷机风险，操作前请备份，风险自负。
