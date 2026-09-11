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

## 支持列表（v1.8.9）

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
| `com.txznet.txz`（TXZ 语音主服务） | 适配 TXZ 语音音乐命令路由，使"上一曲/下一曲/暂停"走 MusicTool 适配链路而不是系统媒体键（v1.3.2）；v1.7.0 起 hook 云知声 NLU 结果转换出口，本地补抓"播放收藏的歌单/推荐歌单/随便听听"等云端不识别的歌单话术 |

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
- **语音歌单**（v1.8.0 起，QQ音乐HD/车机版）：
  - "播放收藏歌曲 / 收藏的歌单 / 我喜欢的音乐 / 播放我喜欢" → 播 QQ音乐「我喜欢」列表（playFolderType 201，需登录账号；v1.8.1 起冷启动收藏缓存未同步时 4/8/15s 自动重试）
  - "播放猜你喜欢 / 推荐的歌 / 推荐音乐 / 随便听听 / 来点好听的" → QQ音乐个人电台智能推荐流（首页 For You 猜你喜欢同路径，playFolderType 104）
  - "播放每日30首 / 每日推荐" → 取每日30首歌曲列表整列表播放（getSongList type=108 → playSongMid）
  - "播放排行榜 / 榜单 / 热歌榜 / 新歌榜 / 飙升榜" → 取官方排行榜首个榜单歌曲整列表播放（getFolderList type=2 → getSongList type=102 → playSongMid）
  - "收藏这首歌 / 取消收藏" → 收藏当前播放歌曲（v1.8.0 修复：改由 QQ音乐原生收藏链路处理）
  - 歌单话术经 TXZ NLU 本地拦截补抓（云端语料无"歌单/排行榜/每日30首"概念）；v1.8.1 起车助理侧再增关键词兜底——云端误判成点歌时（如"播放我喜欢""收藏的歌丹"）按 title 二次识别直接路由歌单，不依赖 TXZ 进程是否已更新；v1.8.3 扩充"你喜欢""三零"等 ASR 错字覆盖
- **QQ音乐进程稳定性与副作用治理**（v1.8.1）：
  - 修复播放统计协程 NPE 崩溃（ActiveAppManager 活跃第三方包名为 null）——模块进程内反射调用不走 Binder 授权，该字段恒为 null，播放一段时间后 QQ音乐崩溃重启，歌单播放随之失败
  - 自动关闭 QQ音乐"边听边存"（TvPreferences savewhenplay 云控默认开启），不再播放一首就下载一首到本地
- **安装生效与作用域管理**（v1.8.4 起，v1.8.6 修复）：
  - 模块 App「一键勾选作用域并重启应用」：自动把已安装目标包写入 LSPosed 作用域并 enabled=1，然后 force-stop 使其重载模块（替代手动去 LSPosed 管理器勾选+重启）；v1.8.6 起按 LSPosed 1.9.x 真实 schema（modules + scope 两张表）自省读写，修复旧版 chmod 644 导致只读打不开数据库、SQL 表结构假设错误两个问题
  - 模块 App「检测 LSPosed 作用域勾选状态」：root 读取 LSPosed 数据库，列出各 App 勾选状态（v1.8.6 同步适配真实 schema）
  - 模块 App「强制停止作用域应用」：仅重启不修改作用域
  - LSPosed 作用域需勾选：车助理(com.syu.voice)、TXZ语音(com.txznet.txz)、QQ音乐HD(com.tencent.qqmusicpad)
- **日志诊断**（v1.8.5 起）：
  - 导出日志时一并导出 LSPosed 框架注入日志（`lsposed/` 目录 + `logcat_lsposed.txt`），可直接确认模块是否注入目标进程
  - v1.8.6 起模块在每个注入进程的入口/分发/hook 注册处写 `XposedBridge.log`（前缀 `[fyt]`），直接落 LSPosed modules.log——不受 logcat chatty 丢行、文件日志延迟初始化影响，导出后搜 `[fyt]` 即可还原各进程 hook 注册轨迹
  - 「查看运行日志」标注各进程模块加载版本
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
    ├── build.gradle              # compileOnly xposed-api:82；versionCode 10809
    └── src/main/
        ├── AndroidManifest.xml   # LSPosed 声明（v1.8.8 起 xposedscope 含 7 个目标包，含网易云两包）+ MainActivity
        ├── assets/xposed_init    # 入口类
        └── java/com/syu/voice/hook/   # v1.8.9
            ├── MainHook.java         # Xposed 入口（进程分流；v1.8.6 XposedBridge.log 全链路埋点；v1.8.7 attachBaseContext+initEarly；v1.8.9 改 hook ContextWrapper+白名单注入提前独立容错）
            ├── TXZHook.java          # TXZ 主服务 hook（v1.3.2 命令路由；v1.7.0 NLU 歌单拦截；v1.8.0 哨兵；v1.8.1 文件日志初始化；v1.8.6 LSPosed 日志埋点；v1.8.9 attachBaseContext 改 hook ContextWrapper）
            ├── QQProcessHook.java    # QQ音乐进程 hook（AIDL + 缓存补发；v1.7.0 action=30 歌单；v1.8.0 m0=5/6 放行；v1.8.1 防崩溃 hook + 关闭边听边存；v1.8.6 LSPosed 日志埋点；v1.8.7 pad类名+initEarly；v1.8.9 attachBaseContext 改 hook ContextWrapper）
            ├── ApiHolder.java        # ApiMethodsImpl 实例持有 + voicePlay/控制/playFolderType/getSongList/playSongMid；v1.8.1 收藏 101 自动重试
            ├── MusicToolInject.java  # 白名单注入（5 个目标包名）
            ├── QQMusicToolProxy.java # MusicTool 动态代理（转发 + 状态上报；v1.8.0 哨兵路由；v1.8.1 歌单关键词兜底；v1.8.3 ASR 错字扩充；v1.8.6 "xxx喜欢"结尾兜底）
            ├── QQMusicController.java# 控制广播 + 进程拉起（ensureRunning；action=30 歌单 m0=201/104/108/2；v1.8.3 原生 action 兜底）
            ├── LogManager.java       # Logcat + 文件日志（多进程独立目录；v1.8.7 initEarly 无 Context 早期初始化）
            ├── ContextHolder.java    # Application Context 持有
            ├── UpdateManager.java    # GitHub Releases 检查/下载/安装
            ├── MainActivity.java     # 模块 UI 编排（v1.8.8 起纯 UI/线程/对话框，业务下沉到下列 5 个类；526 行）
            ├── ScopeConfig.java      # v1.8.8 常量：模块包名/7 个作用域包/日志包列表/LSPosed DB 路径
            ├── RootShell.java        # v1.8.8 root 执行：suRun/readAll/root 缓存/pidof/reboot
            ├── LogCollector.java     # v1.8.8 各进程日志读取/清空/zip 导出（ExportResult）
            ├── LsposedScopeManager.java # v1.8.8 modules_config.db schema 自省/作用域写入/只读检测
            └── AppControl.java       # v1.8.8 am force-stop 作用域应用 + pid 前后比对
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
| v1.8.9 | **修复 v1.8.7 起车助理设置选不到 QQ音乐/网易云等音乐源的回归**：①`attachBaseContext(Context)` 实际声明在父类 `android.content.ContextWrapper` 上、Application 自身未声明，v1.8.7 hook "android.app.Application" 在 Android10/LSPosed1.9.2 抛 NoSuchMethodError#exact——车助理分支该注册无独立 try/catch，异常中止整个 handleLoadPackage，MusicToolInject 白名单注入完全没执行；三入口（MainHook/TXZHook/QQProcessHook）统一改 hook ContextWrapper + 回调 instanceof Application 过滤；②车助理分支 MusicToolInject.hook 提前到生命周期 hook 之前并独立 try/catch，外层 catch 不再 rethrow（当前版本） |
| v1.8.8 | 工程治理：①manifest xposedscope 补齐网易云两包（文档记 v1.4.1 已加但实际从未包含，LSPosed 管理器无法勾选；此前靠一键勾选写 DB 绕过）；②MainActivity 1540 行拆成 UI + ScopeConfig/RootShell/LogCollector/LsposedScopeManager/AppControl 五个职责类（行为不变，删两处死代码）；③cert.txt 换成真实签名信息；④SDK 迁到 D:\android-sdk（当前版本） |
| v1.8.7 | ①hook Application.attachBaseContext 替代/补充 onCreate——反编译发现 MusicApplication.onCreate 的 super.onCreate() 被 SwordProxy 云控条件跳过，导致 Application.onCreate 回调不触发；attachBaseContext 由框架调用且子类不可能跳过；②LogManager.initEarly() 不依赖 Context 在 handleLoadPackage 入口即写文件日志；③BroadcastReceiver/ApiService 尝试 qqmusicpad 子类名；④所有 hook 步骤补全 xlog |
| v1.8.6 | ①模块入口/分发/hook 注册全链路改走 XposedBridge.log（`[fyt]` 前缀，直接落 LSPosed modules.log，导出可查），解决"模块类已加载但 hook 是否执行无法证实"的盲区；②重写作用域读写：sqlite_master 自省适配 LSPosed 1.9.x 真实 schema（modules+scope 两表），修复一键勾选 chmod 644 只读打不开数据库、检测 SQL 表结构假设错误两个 bug；写回 cp 覆盖保留属主/SELinux 上下文；③收藏关键词兜底扩充：title 以"喜欢"结尾（如 ASR 误识"china喜欢"）→ 收藏歌单 |
| v1.8.5 | 导出日志时一并导出 LSPosed 框架注入日志：root 打包 `/data/adb/lspd/log/` 等路径日志到 `lsposed/` 目录；logcat 按 LSPosed/LSPosed-Bridge/LSPosedManager/Xposed 标签过滤生成 `logcat_lsposed.txt`；info.txt 列出 LSPosed 日志文件清单 |
| v1.8.4 | 「强制停止作用域应用」按钮升级为「一键勾选作用域并重启应用」：自动定位 LSPosed 配置数据库，把已安装目标包合并写入模块 scope 并 enabled=1，备份+清 WAL+恢复权限属主，然后 force-stop 所有目标应用重载模块；全过程记录日志 |
| v1.8.3 | 定位 v1.8.2 收藏失败根因：LSPosed 作用域未勾选 QQ音乐HD/TXZ → action=30 广播被原生丢弃。修复：①playFolder 增加 QQ 原生 action 兜底（收藏→action=4，排行榜→action=7）；②车助理侧关键词兜底扩充"你喜欢""三零"等 ASR 错字；③模块 App 新增"检测 LSPosed 作用域勾选状态"按钮；④parseLoadedVersion 取最新加载版本 |
| v1.8.2 | 模块 App 新增"强制停止作用域应用"按钮：一键 `am force-stop` 所有作用域宿主进程使其重载模块代码；记录每个 App 停止前后 pid；修复模块 App 进程此前从未初始化文件日志的问题 |
| v1.8.1 | 修复 v1.8.0 实测两大问题：①QQ音乐HD 播放统计协程 NPE 崩溃（ActiveAppManager 活跃第三方包名为 null——模块进程内反射不走 Binder 授权，该字段恒为 null），hook b() null 兜底 + 主动 f("com.syu.voice")；②QQ音乐"边听边存"云控默认开启导致播放即下载，hook TvPreferences.d0() 强制 false + n1(false) 持久化关闭。收藏播放冷启动 code=101（本地收藏缓存空）按 4/8/15s 自动重试；车助理侧新增歌单关键词兜底（云端把"我喜欢/收藏的歌单"误判成点歌时直接路由，不依赖 TXZ 进程更新）；TXZ 进程补文件日志初始化；日志查看/导出标注各进程模块加载版本，缺进程给出 LSPosed 作用域指引 |
| v1.8.0 | 歌单二期：新增语音直放"每日30首"（getSongList type=108→playSongMid）、"排行榜/热歌榜/新歌榜"（getFolderList type=2→getSongList type=102→playSongMid）；"推荐"话术语义对齐首页"猜你喜欢"（个人电台 104）；NLU 对云端误识别为点歌的歌单话术强制覆盖（修复"收藏的歌单"被 ASR 成"收藏的歌丹"当歌名搜索）；修复 v1.7.0 回归——"收藏这首歌"被 hook 误拦截（m0=5/6 改放行原生 receiver）；歌单指令经哨兵 model.title 跨进程路由 |
| v1.7.0 | 新增语音歌单："播放收藏的歌单/我喜欢的音乐"→QQ音乐「我喜欢」（playFolderType 201，需登录）；"播放推荐歌单/每日推荐/随便听听"→QQ音乐个人电台推荐流（playFolderType 104）；"收藏/取消收藏这首歌"接线原生 m0=5/6。TXZ 侧 hook 云知声 NLU 转换出口本地补抓歌单话术（云端语料无"歌单"概念，原版回"不知道你在说啥"） |
| v1.6.4 | 修复冷启动点歌不播放：QQ音乐被强停后语音点歌只打开不播放（广播早于播放器进程 hook 就绪被原生抢走）；冷启动自动 4/9/15/25s 重发广播，拦截端 20s/6s 去重防重复 |
| v1.6.3 | 可观测性修复：logcat 导出 5000→30 万行；播放器进程文件日志不落盘修复；解码端自愈（旧广播空格还原 '+'）；导出补 /data/media/0 等候选路径 |
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
