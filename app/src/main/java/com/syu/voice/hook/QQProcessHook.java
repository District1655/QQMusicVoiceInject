package com.syu.voice.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ音乐进程内注入（v1.3.0 新增，v1.3.1 增强）。
 *
 * 目标进程：com.tencent.qqmusiccar（车机版）/ com.tencent.qqmusicpad（HD/Pad版）/
 * com.tencent.qqmusic（手机版）。用户需在 LSPosed 作用域中额外勾选已安装的 QQ音乐包。
 *
 * 做四件事：
 * 1. 捕获 ApiMethodsImpl 实例（hook ApiMethodsImpl 私有构造 + QQMusicApiService.onCreate），
 *    供 ApiHolder 进程内直接调用官方 AIDL 后台播放接口；
 * 2. hook 播放控制的前置检查 QQMusicServiceProxyHelper.m()，强制返回 true：
 *    反编译确认 playMusic/pauseMusic/skipToNext/skipToPrevious/stopMusic 第一步都检查
 *    m()（PlayerService 绑定状态），为 false 直接返回错误码 11、什么都不做。
 *    我们强制放行后，控制会走到 PlayListProxyManager（其内部自己 bind PlayerService）。
 * 3. hook BroadcastReceiverCenterForThird.onReceive（父类，pad 子类继承）：
 *    - action=8 点歌 -> 拦截 -> ApiHolder.voicePlay(query) 后台搜索直接播放（不弹搜索框 UI）
 *    - action=20 播放控制 -> 拦截 -> ApiHolder.control(m0, m1) 直接调播放器
 *    ApiMethodsImpl 实例未就绪时缓存命令（pending），实例就绪后自动补发；
 * 4. Application.onCreate 时主动 bind QQMusicApiService，确保 ApiMethodsImpl 实例存在。
 */
public final class QQProcessHook {

    public static final String TAG = MainHook.TAG;

    private static final String RECEIVER_CAR =
            "com.tencent.qqmusiccar.app.reciver.BroadcastReceiverCenterForThird";
    private static final String API_SERVICE_CAR =
            "com.tencent.qqmusiccar.third.api.QQMusicApiService";
    private static final String API_IMPL =
            "com.tencent.qqmusiccar.third.api.apiImpl.ApiMethodsImpl";
    private static final String SERVICE_PROXY_HELPER =
            "com.tencent.qqmusic.qplayer.core.player.proxy.QQMusicServiceProxyHelper";
    /** v1.8.1：当前"活跃第三方包名"管理器，b() 返回 null 会导致播放统计协程 NPE 崩溃 */
    private static final String ACTIVE_APP_MANAGER =
            "com.tencent.qqmusiccar.third.api.ActiveAppManager";
    /** v1.8.1：车机偏好设置，d0()="边听边存"开关（默认随云控开启），开启后播放即下载到本地 */
    private static final String TV_PREFERENCES =
            "com.tencent.qqmusiccar.common.sp.TvPreferences";
    /** 活跃第三方包名兜底值：本模块所有语音指令实际来自方易通车助理 */
    private static final String FALLBACK_ACTIVE_PKG = "com.syu.voice";

    // ------------------------------------------------------------------
    // pending：ApiMethodsImpl 未就绪时缓存命令，就绪后自动补发
    // ------------------------------------------------------------------
    private static volatile String sPendingQuery;
    private static volatile int sPendingCmd = -2;   // -2 表示无 pending 控制
    private static volatile long sPendingExtra;
    private static volatile int sPendingFolder = -2; // -2 表示无 pending 歌单（201收藏/104猜你喜欢/108每日30首/2排行榜）
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static volatile boolean sPolling;
    private static volatile int sPollAttempts;

    // 冷启动重发去重：发送端（车助理进程）冷启动时会在 4/9/15/25s 重发同一广播，
    // 拦截端短窗口内相同指令只执行一次，避免重复点歌/重复控制
    private static volatile String sLastPlayQuery;
    private static volatile long sLastPlayTime;
    private static volatile int sLastCmd = -2;      // -2 表示尚无控制指令
    private static volatile long sLastCmdExtra;
    private static volatile long sLastCmdTime;
    private static volatile int sLastFolder = -2;   // -2 表示尚无歌单指令（v1.7.0）
    private static volatile long sLastFolderTime;

    private QQProcessHook() {
    }

    /** 是否 QQ音乐目标包 */
    public static boolean isQQMusicPkg(String pkg) {
        return "com.tencent.qqmusiccar".equals(pkg)
                || "com.tencent.qqmusicpad".equals(pkg)
                || "com.tencent.qqmusic".equals(pkg);
    }

    public static void hook(final String pkg, final ClassLoader cl) {
        ApiHolder.init(cl);

        // 1) hook ApiMethodsImpl 私有构造：任何路径创建都捕获实例
        try {
            XposedHelpers.findAndHookConstructor(API_IMPL, cl, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ApiHolder.set(param.thisObject);
                    maybeFlushPending();
                }
            });
            LogManager.d(TAG, "[" + pkg + "] hook ApiMethodsImpl 构造成功");
        } catch (Throwable t) {
            LogManager.d(TAG, "[" + pkg + "] hook ApiMethodsImpl 构造跳过: " + t.getMessage());
        }

        // 2) hook QQMusicApiService.onCreate：拿 service -> e(QQMusicApiImpl) -> e(ApiMethodsImpl)
        try {
            XposedHelpers.findAndHookMethod(API_SERVICE_CAR, cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            captureFromService(param.thisObject);
                            maybeFlushPending();
                        }
                    });
            LogManager.d(TAG, "[" + pkg + "] hook QQMusicApiService.onCreate 成功");
        } catch (Throwable t) {
            LogManager.d(TAG, "[" + pkg + "] hook QQMusicApiService.onCreate 跳过: " + t.getMessage());
        }

        // 3) hook 播放控制前置检查：强制跳过 PlayerService 绑定检查
        //    反编译确认：m() 返回 false 时所有播放控制方法直接 return 11，什么都不做
        try {
            XposedHelpers.findAndHookMethod(SERVICE_PROXY_HELPER, cl, "m",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            LogManager.d(TAG, "[" + pkg + "] 强制放行 PlayerService 检查 (m() -> true)");
                            param.setResult(true);
                        }
                    });
            LogManager.i(TAG, "[" + pkg + "] 已 hook QQMusicServiceProxyHelper.m()，播放控制不再受 PlayerService 检查拦截");
        } catch (Throwable t) {
            LogManager.e(TAG, "[" + pkg + "] hook QQMusicServiceProxyHelper.m() 失败", t);
        }

        // 4) hook 第三方控制广播接收器：拦截点歌/播放控制，改走内部 API 后台播放
        try {
            XposedHelpers.findAndHookMethod(RECEIVER_CAR, cl,
                    "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.args[0];
                                Intent intent = (Intent) param.args[1];
                                if (interceptBroadcast(intent)) {
                                    param.setResult(null); // 消费广播，阻止弹搜索框/原逻辑
                                }
                            } catch (Throwable t) {
                                LogManager.e(TAG, "拦截广播异常，放行原逻辑", t);
                            }
                        }
                    });
            LogManager.i(TAG, "[" + pkg + "] 已 hook 第三方控制广播（点歌/播放控制将走内部 API 后台播放）");
        } catch (Throwable t) {
            LogManager.e(TAG, "[" + pkg + "] hook BroadcastReceiverCenterForThird 失败", t);
        }

        // 5) Application.onCreate：初始化日志 + 主动 bind ApiService 确保实例存在
        //    （包 try/catch：此 hook 失败不能影响 1~4 已注册的广播拦截；
        //      且失败必须落 logcat——否则播放器进程只有拦截日志、没有文件日志，极难排查）
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final Context app = (Context) param.thisObject;
                            ContextHolder.set(app);
                            LogManager.init(app);
                            try {
                                Context moduleCtx = app.createPackageContext(
                                        "com.syu.voice.hook", Context.CONTEXT_IGNORE_SECURITY);
                                boolean logEnabled = moduleCtx.getSharedPreferences(
                                        "fyt_music_voice_prefs", Context.MODE_PRIVATE)
                                        .getBoolean("log_enabled", true);
                                LogManager.setEnabled(logEnabled);
                            } catch (Throwable t) {
                                LogManager.w(TAG, "[" + pkg + "] 读取日志开关失败，默认开启");
                            }
                            LogManager.i(TAG, "[" + pkg + "] 模块加载（QQ音乐进程）v"
                                    + BuildConfig.VERSION_NAME + " 日志文件="
                                    + LogManager.getLogFile());
                            ensureApiService(app, pkg);
                            applyEnvironmentFixes(pkg);
                        }
                    });
        } catch (Throwable t) {
            LogManager.e(TAG, "[" + pkg + "] hook Application.onCreate 失败（文件日志不可用）", t);
        }

        // 6) v1.8.1 运行环境修复（防崩溃 + 防偷跑下载），与 1~5 互不依赖：
        //    a. ActiveAppManager.b() 返回 null 时兜底非空包名——
        //       反编译确认播放统计协程 PlayerServiceHelper$initPlayerProcessCallback$1.f()
        //       对 b() 结果做 Intrinsics.g 非空断言，null 即 NPE 直接崩溃（215157 日志实测）。
        //       该字段只有第三方 Binder AIDL 调用经过 checkPermission 时才被赋值，
        //       模块是进程内反射调用、不走 Binder，不 hook 时该字段恒为 null。
        //    b. TvPreferences.d0()（"边听边存"开关，云控 conf_listen_and_save 默认 true）
        //       强制返回 false——开启时 MusicPlayerHelper 会把播放缓存落成本地文件，
        //       用户看到"莫名其妙下载了歌曲"。
        try {
            Class<?> aamCls = XposedHelpers.findClass(ACTIVE_APP_MANAGER, cl);
            XposedHelpers.findAndHookMethod(aamCls, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() == null) {
                        param.setResult(FALLBACK_ACTIVE_PKG);
                        LogManager.i(TAG, "[" + pkg + "] ActiveAppManager.b() 为 null，已兜底 "
                                + FALLBACK_ACTIVE_PKG + "（防播放统计 NPE 崩溃）");
                    }
                }
            });
            LogManager.i(TAG, "[" + pkg + "] 已 hook ActiveAppManager.b()（null 兜底防崩溃）");
        } catch (Throwable t) {
            LogManager.e(TAG, "[" + pkg + "] hook ActiveAppManager.b() 失败", t);
        }
        try {
            Class<?> tvpCls = XposedHelpers.findClass(TV_PREFERENCES, cl);
            XposedHelpers.findAndHookMethod(tvpCls, "d0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Boolean && (Boolean) r) {
                        param.setResult(Boolean.FALSE);
                        LogManager.i(TAG, "[" + pkg + "] TvPreferences.d0()=true（边听边存开启），"
                                + "已强制返回 false，防止播放时自动下载歌曲到本地");
                    }
                }
            });
            LogManager.i(TAG, "[" + pkg + "] 已 hook TvPreferences.d0()（强制关闭边听边存）");
        } catch (Throwable t) {
            LogManager.e(TAG, "[" + pkg + "] hook TvPreferences.d0() 失败", t);
        }
    }

    /**
     * v1.8.1：主动写一次运行环境（hook 的双保险），在 Application.onCreate 延迟 2.5s 执行：
     * 1. ActiveAppManager.a().f(pkg)：直接把"活跃第三方包名"写非空，
     *    避免统计协程在 hook 注册前的窗口空指针崩溃；
     * 2. TvPreferences.r().n1(false)：把"边听边存"持久化关闭（QQ 设置页同步显示关闭）。
     */
    private static void applyEnvironmentFixes(final String pkg) {
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                ClassLoader cl = ApiHolder.getClassLoader();
                if (cl == null) {
                    LogManager.w(TAG, "[" + pkg + "] 环境修复跳过：ClassLoader 未就绪");
                    return;
                }
                try {
                    Class<?> aamCls = XposedHelpers.findClass(ACTIVE_APP_MANAGER, cl);
                    Object aam = XposedHelpers.callStaticMethod(aamCls, "a");
                    XposedHelpers.callMethod(aam, "f", FALLBACK_ACTIVE_PKG);
                    LogManager.i(TAG, "[" + pkg + "] 已主动设置 ActiveAppManager 活跃包名="
                            + FALLBACK_ACTIVE_PKG);
                } catch (Throwable t) {
                    LogManager.w(TAG, "[" + pkg + "] 主动设置 ActiveAppManager 失败: "
                            + t.getMessage());
                }
                try {
                    Class<?> tvpCls = XposedHelpers.findClass(TV_PREFERENCES, cl);
                    Object tvp = XposedHelpers.callStaticMethod(tvpCls, "r");
                    XposedHelpers.callMethod(tvp, "n1", false);
                    LogManager.i(TAG, "[" + pkg + "] 已持久化关闭 QQ音乐\"边听边存\""
                            + "（播放不再自动下载歌曲）");
                } catch (Throwable t) {
                    LogManager.w(TAG, "[" + pkg + "] 持久化关闭边听边存失败: "
                            + t.getMessage());
                }
            }
        }, 2500);
    }

    /** 从 QQMusicApiService 实例反射取 e.e 得到 ApiMethodsImpl */
    private static void captureFromService(Object service) {
        try {
            Object apiImpl = XposedHelpers.getObjectField(service, "e"); // QQMusicApiImpl
            Object methodsImpl = XposedHelpers.getObjectField(apiImpl, "e"); // ApiMethodsImpl
            ApiHolder.set(methodsImpl);
        } catch (Throwable t) {
            LogManager.w(TAG, "从 QQMusicApiService 取 ApiMethodsImpl 失败: " + t.getMessage());
        }
    }

    /** 主动 bind 第三方 API 服务，确保 ApiMethodsImpl 被创建 */
    private static void ensureApiService(final Context app, final String pkg) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String serviceClass = pkg + ".third.api.QQMusicApiService";
                    Intent intent = new Intent();
                    intent.setClassName(pkg, serviceClass);
                    boolean ok = app.bindService(intent, new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            LogManager.i(TAG, "[" + pkg + "] ApiService 已连接: " + name);
                            if (!ApiHolder.isReady()) {
                                LogManager.w(TAG, "[" + pkg + "] 连接成功但 ApiMethodsImpl 未就绪");
                                startPolling();
                            }
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                        }
                    }, Context.BIND_AUTO_CREATE);
                    LogManager.i(TAG, "[" + pkg + "] 主动 bind ApiService(" + serviceClass + ") -> " + ok);
                    if (ok && !ApiHolder.isReady()) {
                        startPolling(); // 兜底轮询，等待构造 hook 捕获实例
                    }
                } catch (Throwable t) {
                    LogManager.e(TAG, "[" + pkg + "] bind ApiService 失败", t);
                }
            }
        }, 1000);
    }

    // ------------------------------------------------------------------
    // 广播拦截
    // ------------------------------------------------------------------

    /** 拦截 qqmusicpad:// / qqmusiccar:// 广播中的 action=8（点歌）与 action=20（控制） */
    private static boolean interceptBroadcast(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri uri = intent.getData();
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"qqmusicpad".equals(scheme) && !"qqmusiccar".equals(scheme)) {
            return false;
        }
        String actionStr = uri.getQueryParameter("action");
        if (actionStr == null || actionStr.isEmpty()) {
            return false;
        }
        int action;
        try {
            action = Integer.parseInt(actionStr);
        } catch (Throwable t) {
            return false;
        }
        try {
            if (action == 8) {
                // 点歌：search_key -> 后台 voicePlay
                String key = uri.getQueryParameter("search_key");
                String query = ApiHolder.decodeSearchKey(key);
                LogManager.i(TAG, "[拦截] 点歌 action=8 search_key=" + key + " -> query=" + query);
                if (query != null && !query.trim().isEmpty()) {
                    // 去重：发送端冷启动会在 4/9/15/25s 重发同一广播，20 秒内相同 query
                    // 已处理过（voicePlay 成功或已缓存待补发）则直接消费，不重复点歌
                    long now = SystemClock.uptimeMillis();
                    if (query.equals(sLastPlayQuery) && now - sLastPlayTime < 20000) {
                        LogManager.i(TAG, "[去重] " + (now - sLastPlayTime)
                                + "ms 内相同点歌已处理，消费重发广播不重复播放: " + query);
                        return true;
                    }
                    sLastPlayQuery = query;
                    sLastPlayTime = now;
                    if (ApiHolder.voicePlay(query)) {
                        LogManager.i(TAG, ">>> 已走内部 API 后台播放（不弹搜索框）");
                        return true;
                    }
                    LogManager.w(TAG, "voicePlay 未就绪，缓存命令待实例就绪后补发");
                    sPendingQuery = query;
                    startPolling();
                    return true; // 消费广播（不弹搜索框），由 pending 兜底
                }
                return false;
            }
            if (action == 20) {
                // 播放控制：m0=0/1/2/3/7/8/9/101/103/105
                String m0 = uri.getQueryParameter("m0");
                String m1 = uri.getQueryParameter("m1");
                int cmd = -1;
                long extra = 0;
                try {
                    cmd = Integer.parseInt(m0);
                    extra = Long.parseLong(m1);
                } catch (Throwable ignored) {
                }
                // v1.7.1 修复：m0=5(收藏当前歌曲)/6(取消收藏) 原生 receiver 自己处理
                //（MusicPlayerHelper 收藏红心），模块 action=20 一旦消费原生就收不到，
                // 而 ApiHolder.control 也不实现 5/6 → "收藏这首歌"曾被拦截破坏。这里放行给原生，
                // 但 6s 内的冷启动重发广播直接消费掉（收藏指令幂等，无需重复投递）。
                if (cmd == 5 || cmd == 6) {
                    long favNow = SystemClock.uptimeMillis();
                    if (cmd == sLastCmd && extra == sLastCmdExtra && favNow - sLastCmdTime < 6000) {
                        LogManager.i(TAG, "[去重] 收藏类指令重发，消费不重复投递原生: cmd=" + cmd);
                        return true;
                    }
                    sLastCmd = cmd;
                    sLastCmdExtra = extra;
                    sLastCmdTime = favNow;
                    LogManager.i(TAG, "[放行] action=20 m0=" + cmd
                            + "（" + (cmd == 5 ? "收藏" : "取消收藏") + "当前歌曲）交原生 receiver 处理");
                    return false;
                }
                LogManager.i(TAG, "[拦截] 播放控制 action=20 m0=" + m0 + " m1=" + m1);
                // 去重：冷启动重发的相同控制指令 6 秒内只执行一次
                long now = SystemClock.uptimeMillis();
                if (cmd == sLastCmd && extra == sLastCmdExtra && now - sLastCmdTime < 6000) {
                    LogManager.i(TAG, "[去重] " + (now - sLastCmdTime)
                            + "ms 内相同控制指令已处理，消费重发广播: cmd=" + cmd);
                    return true;
                }
                sLastCmd = cmd;
                sLastCmdExtra = extra;
                sLastCmdTime = now;
                if (ApiHolder.control(cmd, extra)) {
                    LogManager.i(TAG, ">>> 已走内部 API 播放控制");
                    return true;
                }
                LogManager.w(TAG, "control 未就绪，缓存命令待实例就绪后补发");
                sPendingCmd = cmd;
                sPendingExtra = extra;
                startPolling();
                return true; // 消费广播，由 pending 兜底
            }
            if (action == 30) {
                // 歌单播放（v1.7.1）：m0=201 收藏歌曲，104 猜你喜欢（个人电台），
                // 108 每日30首（取列表→playSongMid），2 排行榜（取榜单→取歌曲→播放）
                String m0 = uri.getQueryParameter("m0");
                int folderType = -1;
                try {
                    folderType = Integer.parseInt(m0);
                } catch (Throwable ignored) {
                }
                LogManager.i(TAG, "[拦截] 歌单播放 action=30 m0=" + m0);
                // 去重：冷启动重发的相同歌单指令 6 秒内只执行一次
                long now = SystemClock.uptimeMillis();
                if (folderType == sLastFolder && now - sLastFolderTime < 6000) {
                    LogManager.i(TAG, "[去重] " + (now - sLastFolderTime)
                            + "ms 内相同歌单指令已处理，消费重发广播: folderType=" + folderType);
                    return true;
                }
                sLastFolder = folderType;
                sLastFolderTime = now;
                if (ApiHolder.playFolderCmd(folderType)) {
                    LogManager.i(TAG, ">>> 已走内部 API 歌单播放: " + folderType);
                    return true;
                }
                LogManager.w(TAG, "playFolderCmd 未就绪，缓存命令待实例就绪后补发");
                sPendingFolder = folderType;
                startPolling();
                return true; // 消费广播（原生不识别 action=30，也必须消费），由 pending 兜底
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "拦截 action=" + action + " 异常", t);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // pending 补发
    // ------------------------------------------------------------------

    private static void startPolling() {
        if (sPolling) {
            return;
        }
        sPolling = true;
        sPollAttempts = 0;
        sHandler.postDelayed(sPollRunnable, 500);
    }

    private static final Runnable sPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (ApiHolder.isReady()) {
                sPolling = false;
                maybeFlushPending();
                return;
            }
            sPollAttempts++;
            if (sPollAttempts < 40) { // 最多等 20 秒
                sHandler.postDelayed(this, 500);
            } else {
                sPolling = false;
                LogManager.w(TAG, "等待 ApiMethodsImpl 就绪超时（20s），丢弃 pending 命令");
                sPendingQuery = null;
                sPendingCmd = -2;
                sPendingFolder = -2;
            }
        }
    };

    /** 实例就绪后补发缓存的点歌/控制/歌单命令 */
    private static void maybeFlushPending() {
        if (!ApiHolder.isReady()) {
            return;
        }
        String query = sPendingQuery;
        if (query != null) {
            sPendingQuery = null;
            LogManager.i(TAG, "[pending] 补发点歌 -> " + query);
            ApiHolder.voicePlay(query);
        }
        int cmd = sPendingCmd;
        if (cmd != -2) {
            sPendingCmd = -2;
            long extra = sPendingExtra;
            LogManager.i(TAG, "[pending] 补发播放控制 -> cmd=" + cmd + " extra=" + extra);
            ApiHolder.control(cmd, extra);
        }
        int folder = sPendingFolder;
        if (folder != -2) {
            sPendingFolder = -2;
            LogManager.i(TAG, "[pending] 补发歌单播放 -> folderType=" + folder);
            ApiHolder.playFolderCmd(folder);
        }
    }
}
