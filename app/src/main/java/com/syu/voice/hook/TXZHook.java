package com.syu.voice.hook;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * TXZ 语音主服务（com.txznet.txz）进程 hook。
 *
 * v1.3.2 新增：反编译确认 TXZ 音乐模块（com.txznet.txz.module.music.b）在收到
 * "next/prev/pause/switchSong" 等控制命令时：
 *   if (y()) { MediaControlUtil.next(); }   // y()==true → 发系统媒体键（QQ音乐HD 无
 *                                           // MediaSession，收不到 → 完全没反应）
 *   else { a("next", pkg); iMusicR2.next(); } // y()==false → 走 MusicTool 代理（我们的链路）
 *
 * y() = com.txznet.txz.module.d.a.a().b()（当前是否存在"音频工具"如喜马拉雅/蜻蜓等）。
 * 只要车机上装了 TXZ 支持的音频 App 并被设为当前工具，y() 就是 true，
 * 控制指令会全部落到系统媒体键，绕过我们的 MusicTool 代理。
 *
 * 这里把 music.b 的 y() 强制改为 false，确保所有音乐控制命令都走 MusicTool 分支，
 * 由模块代理转发给 QQ音乐 AIDL 控制接口（v1.3.1 已修 PlayerService 前置检查）。
 *
 * v1.7.0 新增：NLU 本地拦截（歌单话术）。反编译 TXZ_2.9.8 确认云知声音乐技能
 * （TextYunzhishengImpl.parseOLMusic）只支持 scene=="收藏" → playFavourMusic、
 * SEARCH_RANDOM → playRandom，语料中没有"歌单/排行榜/每日30首"概念——这类话术
 * 云端 NLU 要么不识别为音乐域，要么误识别成点歌（实测"收藏的歌单"被 ASR 成
 * "收藏的歌丹"后当歌名搜索）。hook yzsDataToTxzScene（云知声结果 → TXZ 场景
 * json 的转换出口），按 ASR 原文本地匹配并伪造场景 json：
 *   收藏/我喜欢 + 音乐语境（非"这首歌"）→ playFavourMusic → QQ playFolderType(201)
 *   猜你喜欢/推荐…/随便听听/好听的       → playRandom       → QQ playFolderType(104)
 *   每日30首/每日推荐                     → play + 哨兵 model → QQ getSongList(108)+playSongMid
 *   排行榜/榜单/热歌榜…                   → play + 哨兵 model → QQ getFolderList(2)+getSongList(102)
 * 之后链路与正常语音指令完全一致：music.b → MusicTool 代理 → QQ音乐内部 API。
 *
 * v1.7.1：云端已识别为音乐域时，若是收藏/取消收藏当前歌曲（favourMusic）不干预，
 * 其余（误识别成点歌等）一律按本地歌单话术覆盖；新增排行榜/每日30首两类哨兵路由。
 */
public final class TXZHook {

    public static final String TAG = MainHook.TAG;

    /** 哨兵 model.title：车助理进程 QQMusicToolProxy.playMusic 识别后路由到对应歌单，不会进搜索 */
    public static final String SENTINEL_DAILY30 = "@@fyt_playlist_daily30@@";
    public static final String SENTINEL_RANK = "@@fyt_playlist_rank@@";

    private static final String TEXT_IMPL =
            "com.txznet.txz.component.text.yunzhisheng_3_0.TextYunzhishengImpl";
    private static final String VOICE_PARSE_DATA = "com.txz.ui.voice.VoiceData$VoiceParseData";

    private TXZHook() {
    }

    /** 本地话术匹配结果：action=MusicTool 方法名；sentinel 非空时伪造 play 指令并把哨兵放 model.title */
    static final class PlaylistMatch {
        final String action;
        final String sentinel;
        final String desc;

        PlaylistMatch(String action, String sentinel, String desc) {
            this.action = action;
            this.sentinel = sentinel;
            this.desc = desc;
        }
    }

    /** v1.8.7：attachBaseContext/onCreate 双保险防重复初始化 */
    private static volatile boolean sAppInited;

    public static void hook(ClassLoader cl) {
        // v1.8.6：入口立即打 LSPosed 日志，确认 TXZ 进程确实进入 hook() 注册阶段
        MainHook.xlog("TXZHook.hook() 进入 cl=" + (cl == null ? "null" : cl.getClass().getName()));
        // v1.8.9：attachBaseContext 必须 hook 在 android.content.ContextWrapper 上——
        // Application 自身未声明该方法（继承自 ContextWrapper），直接 hook
        // "android.app.Application" 在 Android 10/LSPosed 1.9.2 上抛
        // NoSuchMethodError#exact（v1.8.7/1.8.8 实测日志）。ContextWrapper 的
        // attachBaseContext 也会被 Service 等调用，回调里 instanceof Application 过滤。
        // attachBaseContext 早于 onCreate，防止子类跳过 super.onCreate() 导致初始化丢失。
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", cl,
                    "attachBaseContext", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sAppInited || !(param.thisObject instanceof Application)) return;
                            sAppInited = true;
                            Context app = (Context) param.thisObject;
                            ContextHolder.set(app);
                            LogManager.init(app);
                            MainHook.xlog("TXZ Application.attachBaseContext 已触发 ctx="
                                    + app.getPackageName() + " logFile="
                                    + LogManager.getLogFile());
                            try {
                                Context moduleCtx = app.createPackageContext(
                                        "com.syu.voice.hook", Context.CONTEXT_IGNORE_SECURITY);
                                boolean logEnabled = moduleCtx.getSharedPreferences(
                                        "fyt_music_voice_prefs", Context.MODE_PRIVATE)
                                        .getBoolean("log_enabled", true);
                                LogManager.setEnabled(logEnabled);
                            } catch (Throwable t) {
                                LogManager.w(TAG, "TXZ: 读取日志开关失败，默认开启");
                            }
                            LogManager.i(TAG, "模块加载（TXZ语音进程）v"
                                    + BuildConfig.VERSION_NAME + " 日志文件="
                                    + LogManager.getLogFile());
                        }
                    });
            MainHook.xlog("TXZ hook ContextWrapper.attachBaseContext 注册成功");
        } catch (Throwable t) {
            MainHook.xlog("TXZ hook ContextWrapper.attachBaseContext 失败: " + t);
        }
        // 同时保留 onCreate hook 作为双保险
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sAppInited) return;
                            sAppInited = true;
                            Context app = (Context) param.thisObject;
                            ContextHolder.set(app);
                            LogManager.init(app);
                            MainHook.xlog("TXZ Application.onCreate 已触发 ctx="
                                    + app.getPackageName() + " logFile="
                                    + LogManager.getLogFile());
                            LogManager.i(TAG, "模块加载（TXZ语音进程）v"
                                    + BuildConfig.VERSION_NAME + " 日志文件="
                                    + LogManager.getLogFile());
                        }
                    });
            MainHook.xlog("TXZ Application.onCreate hook 注册成功");
        } catch (Throwable t) {
            MainHook.xlog("TXZ hook Application.onCreate 失败: " + t);
            LogManager.e(TAG, "TXZ hook Application.onCreate 失败（文件日志不可用）", t);
        }

        try {
            // com.txznet.txz.module.music.b（混淆后类名就是 b）私有方法 y() 返回 false
            XposedHelpers.findAndHookMethod("com.txznet.txz.module.music.b", cl,
                    "y", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(false);
                        }
                    });
            LogManager.i(TAG, "TXZ hook: 音乐模块 y() → false（控制命令强制走 MusicTool）");
        } catch (Throwable t) {
            MainHook.xlog("TXZ hook music.b.y() 失败: " + t);
            LogManager.e(TAG, "TXZ hook music.b.y() 失败: " + t.getMessage(), t);
        }

        hookPlaylistNlu(cl);
        MainHook.xlog("TXZHook.hook() 注册阶段全部完成");
    }

    /**
     * hook 云知声 NLU 结果转换出口，本地补抓"歌单/收藏/排行榜/每日30首"话术。
     * yzsDataToTxzScene 是私有静态方法，入参/返回均为 VoiceData$VoiceParseData：
     *   strText      —— ASR 原始文本
     *   strVoiceData —— 转换后的 TXZ 场景 json（{"scene":"music","action":"play",...}）
     */
    private static void hookPlaylistNlu(ClassLoader cl) {
        try {
            Class<?> dataCls = XposedHelpers.findClass(VOICE_PARSE_DATA, cl);
            XposedHelpers.findAndHookMethod(TEXT_IMPL, cl,
                    "yzsDataToTxzScene", dataCls, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object data = param.args[0];
                                if (data == null) {
                                    return;
                                }
                                String text = (String) XposedHelpers.getObjectField(data, "strText");
                                String voice = (String) XposedHelpers.getObjectField(data, "strVoiceData");
                                if (text == null || text.isEmpty()) {
                                    return;
                                }
                                PlaylistMatch match = matchPlaylistAction(text);
                                if (match == null) {
                                    return;
                                }
                                // 云端已识别为音乐域时：收藏/取消收藏当前歌曲（favourMusic）不干预；
                                // 其余（如把"收藏的歌单"误识别成点歌）按本地歌单话术覆盖
                                if (voice != null && voice.contains("\"scene\":\"music\"")) {
                                    if (voice.contains("favourMusic")) {
                                        return;
                                    }
                                }
                                String json;
                                if (match.sentinel != null) {
                                    // 排行榜/每日30首：MusicTool 无对应方法，伪造点歌指令，
                                    // model.title 放哨兵词，车助理侧 playMusic 检测哨兵后直放对应歌单。
                                    // artist/album/keywords 字段补齐为云知声正常点歌时的完整模型结构，
                                    // 避免 TXZ 解析侧字段缺失（参见 module.ae.c 的 t() 模型解析）。
                                    json = "{\"scene\":\"music\",\"action\":\"play\",\"text\":\""
                                            + escapeJson(text)
                                            + "\",\"model\":{\"title\":\"" + match.sentinel
                                            + "\",\"artist\":[],\"album\":\"\",\"keywords\":[]}}";
                                } else {
                                    json = "{\"scene\":\"music\",\"action\":\"" + match.action
                                            + "\",\"text\":\"" + escapeJson(text) + "\"}";
                                }
                                XposedHelpers.setObjectField(data, "strVoiceData", json);
                                LogManager.i(TAG, "NLU 本地拦截歌单话术: \"" + text
                                        + "\" -> " + match.desc
                                        + (voice != null && voice.contains("\"scene\":\"music\"")
                                        ? "（覆盖云端音乐域结果）" : ""));
                            } catch (Throwable t) {
                                LogManager.e(TAG, "NLU 歌单拦截处理异常: " + t.getMessage(), t);
                            }
                        }
                    });
            LogManager.i(TAG, "TXZ hook: NLU 歌单话术本地拦截已注册（收藏/猜你喜欢/每日30首/排行榜）");
        } catch (Throwable t) {
            LogManager.e(TAG, "TXZ hook NLU 歌单拦截失败（TXZ 版本可能不同）: " + t.getMessage(), t);
        }
    }

    /**
     * 本地话术匹配（命中条件刻意收窄，避免误伤导航/电台等其他域）。
     * 优先级：排行榜 > 每日30首 > 收藏 > 猜你喜欢/推荐。
     * "收藏这首歌/我喜欢这首歌"等收藏当前歌曲话术排除（云端可正常识别为 favourMusic）。
     */
    static PlaylistMatch matchPlaylistAction(String text) {
        boolean musicCtx = text.contains("歌") || text.contains("音乐")
                || text.contains("曲") || text.contains("首");

        // 1) 排行榜：排行榜/榜单，或"热歌榜/新歌榜/飙升榜/巅峰榜/音乐榜"等带"榜"的音乐说法
        if (text.contains("排行榜") || text.contains("榜单")
                || (text.contains("榜") && (text.contains("排行") || musicCtx))) {
            return new PlaylistMatch("play", SENTINEL_RANK, "music/排行榜");
        }

        // 2) 每日30首：每日/每天 + 30/三十/推荐/音乐语境
        if ((text.contains("每日") || text.contains("每天"))
                && (text.contains("30") || text.contains("三十")
                || text.contains("推荐") || musicCtx)) {
            return new PlaylistMatch("play", SENTINEL_DAILY30, "music/每日30首");
        }

        // 3) 收藏/我喜欢（播放整个收藏列表）：排除"这首/当前/这个"（那是收藏当前歌曲）；
        //    "播放我喜欢/播放喜欢的"即使不带"歌"字也按收藏列表处理
        if ((text.contains("收藏") || text.contains("我喜欢") || text.contains("喜欢的"))
                && !text.contains("这首") && !text.contains("当前") && !text.contains("这个")
                && (musicCtx || text.contains("歌单")
                || text.contains("我喜欢") || text.contains("喜欢的"))) {
            return new PlaylistMatch("playFavourMusic", null, "music/playFavourMusic(收藏歌曲)");
        }

        // 4) 猜你喜欢 / 推荐 / 随便听听（个人电台 104，即首页"For You 猜你喜欢"）
        if (text.contains("猜你喜欢")
                || (text.contains("推荐") && musicCtx)
                || text.contains("随便听听") || text.contains("随便来")
                || text.contains("来点好听") || text.contains("好听的")) {
            return new PlaylistMatch("playRandom", null, "music/playRandom(猜你喜欢)");
        }
        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
