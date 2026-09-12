package com.syu.voice.hook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 车助理进程内的场景级歌单兜底拦截（v1.8.11 引入，v1.8.12 重写）。
 *
 * v1.8.12 修复（260912 日志实证的两个致命 bug）：
 * 1. 代理识别失败：TXZMusicManager 里 MusicTool 字段在 2.9.8 SDK 混淆为
 *    {@code private Object i}，按声明类型 MusicTool 精确匹配永远找不到，
 *    导致全部命中被误判"非本模块代理"放行。现改为由 MusicToolInject 注册时
 *    直接缓存代理实例，并以车助理"默认音乐工具"包名（VoiceConfigManager
 *    .getDefMusic() / SharedPreferences "com.syu.voice"）校验当前默认源，
 *    字段扫描（Proxy+本模块 Handler）仅作兜底。
 * 2. 去重截断吞链路：旧代码在"放行"分支也写去重键，$17 放行后 $18 第二次
 *    回调被无条件 setResult(true)，云端原链路（IPC playMusic）一起被截断，
 *    指令两头落空。现仅在真正派发成功后才写去重键。
 * 3. 支持 scene=unknown：云端把"播放我喜欢的歌"判成
 *    {"scene":"unknown","action":"unknown"}，非 music 域根本不会走播放链路；
 *    现在非 music 场景只要 ASR 原文命中歌单规则也直接派发。
 */
public final class ScenePlaylistHook {

    public static final String TAG = MainHook.TAG;

    private static final String MUSIC_MANAGER = "com.txznet.sdk.TXZMusicManager";
    private static final String MUSIC_MODEL = "com.txznet.sdk.TXZMusicManager$MusicModel";
    private static final String SCENE_TYPE = "com.txznet.sdk.TXZSceneManager$SceneType";
    private static final String RESOURCE_MANAGER = "com.txznet.sdk.TXZResourceManager";
    private static final String VOICE_CONFIG = "com.syu.voice.VoiceConfigManager";

    /** 车助理保存默认音乐工具包名的 SharedPreferences 名 */
    private static final String VOICE_PREFS = "com.syu.voice";

    private static final String[] SCENE_TOOL_CLASSES = {
            "com.syu.voice.VoiceAdapter$17",
            "com.syu.voice.VoiceAdapter$18",
    };

    /** 注册成功的本模块 MusicTool 代理：包名 -> 代理实例（MusicToolInject 写入） */
    private static final Map<String, Object> sTools = new ConcurrentHashMap<>();

    /** $17/$18 会以同一 JSON 先后回调，仅在"已成功派发"后用于去重 */
    private static volatile String sLastKey = "";
    private static volatile long sLastMs = 0L;

    private ScenePlaylistHook() {
    }

    /** MusicToolInject 注册代理时同步缓存，供场景拦截直接派发 */
    static void registerTool(String pkg, Object tool) {
        if (pkg != null && tool != null) {
            sTools.put(pkg, tool);
        }
    }

    public static void hook(final ClassLoader cl) {
        Class<?> sceneType;
        try {
            sceneType = XposedHelpers.findClass(SCENE_TYPE, cl);
        } catch (Throwable t) {
            LogManager.w(TAG, "场景拦截: 找不到 " + SCENE_TYPE + "，放弃注册: " + t.getMessage());
            return;
        }
        for (final String cls : SCENE_TOOL_CLASSES) {
            try {
                XposedHelpers.findAndHookMethod(cls, cl, "process",
                        sceneType, String.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    intercept(cl, param);
                                } catch (Throwable t) {
                                    LogManager.e(TAG, "场景拦截异常(" + cls + "): " + t.getMessage(), t);
                                }
                            }
                        });
                LogManager.i(TAG, "场景拦截 hook 注册成功: " + cls);
            } catch (Throwable t) {
                LogManager.w(TAG, "场景拦截 hook 注册失败（固件版本差异可忽略）: "
                        + cls + " -> " + t.getMessage());
            }
        }
    }

    private static void intercept(ClassLoader cl, XC_MethodHook.MethodHookParam param)
            throws Exception {
        if (param.args == null || param.args.length < 2
                || !(param.args[1] instanceof String)) {
            return;
        }
        String json = (String) param.args[1];
        if (json.isEmpty()) {
            return;
        }
        JSONObject job = new JSONObject(json);
        String scene = job.optString("scene", "");
        String action = job.optString("action", "");

        String raw;
        if ("music".equals(scene)) {
            // playFavourMusic/playRandom/pause 等云端已正确分类的 action 放行，
            // 只兜底最泛的 "play"（其 model 可能把歌单词塞在 title/keywords）
            if (!"play".equals(action)) {
                return;
            }
            raw = buildMusicRaw(job);
        } else {
            // scene=unknown（实测"播放我喜欢的歌"云端整句判 unknown）等非 music 域：
            // 只信 ASR 原文，命中歌单规则才接管，避免误抢其他域指令
            String text = job.optString("text", "").trim();
            if (text.isEmpty() || QQMusicToolProxy.matchPlaylist(text) < 0) {
                return;
            }
            raw = text;
        }
        if (raw.isEmpty()) {
            return;
        }

        final int folder = QQMusicToolProxy.matchPlaylist(raw);
        if (folder < 0) {
            return; // 非歌单话术（普通点歌/歌手），放行原链路
        }

        Object tool = resolveOurTool(cl);
        if (tool == null) {
            // 当前默认音乐源不是本模块代理（或代理尚未注册）：原样放行，
            // 绝不 setResult、不写去重键——否则 $17/$18 第二条回调会把云端
            // 原链路一起截断（v1.8.11 的两头落空 bug）
            LogManager.d(TAG, "场景拦截: 命中 type=" + folder
                    + " 但当前默认音乐源非本模块代理（注册缓存=" + sTools.keySet()
                    + "），放行原链路");
            return;
        }

        String key = raw + "|" + folder;
        long now = System.currentTimeMillis();
        if (key.equals(sLastKey) && now - sLastMs < 2500L) {
            LogManager.d(TAG, "场景拦截: $17/$18 重复回调，去重截断: " + key);
            param.setResult(Boolean.TRUE);
            return;
        }

        LogManager.i(TAG, "场景拦截命中歌单 type=" + folder + "（scene=" + scene
                + ", action=" + action + "，匹配文本=" + raw
                + "），直接路由并截断车助理场景链");
        boolean ok = dispatch(cl, tool, folder);
        if (ok) {
            sLastKey = key;
            sLastMs = now;
            speak(cl, folder);
            param.setResult(Boolean.TRUE);
        }
    }

    /** music/play 场景：ASR 原文 + model.title + model.keywords 拼接 */
    private static String buildMusicRaw(JSONObject job) {
        String text = job.optString("text", "");
        String title = "";
        StringBuilder keywords = new StringBuilder();
        JSONObject model = job.optJSONObject("model");
        if (model != null) {
            title = model.optString("title", "");
            JSONArray kw = model.optJSONArray("keywords");
            if (kw != null) {
                for (int i = 0; i < kw.length(); i++) {
                    if (kw.isNull(i)) {
                        continue;
                    }
                    String k = kw.optString(i, "");
                    if (!k.isEmpty()) {
                        if (keywords.length() > 0) {
                            keywords.append(' ');
                        }
                        keywords.append(k);
                    }
                }
            }
        }
        StringBuilder combined = new StringBuilder();
        appendPart(combined, text);
        appendPart(combined, title);
        appendPart(combined, keywords.toString());
        return combined.toString().trim();
    }

    /**
     * 找到"当前默认音乐源"对应的本模块代理：
     * 1) 取车助理默认音乐工具包名（VoiceConfigManager，反射失败则扫 SP）；
     * 2) 从注册缓存取代理；
     * 3) 兜底扫描 TXZMusicManager 全部字段值（字段名混淆为 i），
     *    值为动态代理且 InvocationHandler 是本模块 Handler 即采用。
     */
    private static Object resolveOurTool(ClassLoader cl) {
        String defPkg = currentDefaultMusicPkg(cl);
        if (defPkg != null) {
            Object cached = sTools.get(defPkg);
            if (isOurProxy(cached)) {
                return cached;
            }
        }
        Object scanned = scanManagerForProxy(cl);
        if (scanned != null) {
            LogManager.d(TAG, "场景拦截: 默认源(" + defPkg
                    + ")缓存未命中，经 TXZMusicManager 字段扫描找到本模块代理");
            return scanned;
        }
        return null;
    }

    /** 当前默认音乐工具包名；任何途径失败都返回 null */
    private static String currentDefaultMusicPkg(ClassLoader cl) {
        // 途径一：VoiceConfigManager.getInstance().getDefMusic()（$17 原代码同款）
        try {
            Object cfg = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(VOICE_CONFIG, cl), "getInstance");
            Object pkg = XposedHelpers.callMethod(cfg, "getDefMusic");
            if (pkg instanceof String && !((String) pkg).isEmpty()) {
                return (String) pkg;
            }
        } catch (Throwable ignored) {
            // 固件版本差异，走 SP 兜底
        }
        // 途径二：扫 SharedPreferences("com.syu.voice")，值命中本模块已注册包名即用
        try {
            Context ctx = ContextHolder.get();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(
                        VOICE_PREFS, Context.MODE_PRIVATE);
                for (Object v : sp.getAll().values()) {
                    if (v instanceof String && sTools.containsKey(v)) {
                        return (String) v;
                    }
                }
            }
        } catch (Throwable t) {
            LogManager.d(TAG, "场景拦截: 读取默认音乐源 SP 失败: " + t.getMessage());
        }
        return null;
    }

    /** 兜底：遍历 TXZMusicManager 实例（含父类）全部字段，找本模块动态代理 */
    private static Object scanManagerForProxy(ClassLoader cl) {
        try {
            Object mgr = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MUSIC_MANAGER, cl), "getInstance");
            if (mgr == null) {
                return null;
            }
            Class<?> c = mgr.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object value = f.get(mgr);
                        if (isOurProxy(value)) {
                            LogManager.i(TAG, "场景拦截: 字段扫描命中代理 field="
                                    + c.getSimpleName() + "." + f.getName()
                                    + "，声明类型=" + f.getType().getName());
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // 单个字段不可读不影响其他字段
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "场景拦截: 扫描 TXZMusicManager 字段失败", t);
        }
        return null;
    }

    private static boolean isOurProxy(Object tool) {
        if (tool == null || !Proxy.isProxyClass(tool.getClass())) {
            return false;
        }
        try {
            InvocationHandler h = Proxy.getInvocationHandler(tool);
            return h instanceof QQMusicToolProxy.Handler;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean dispatch(ClassLoader cl, Object tool, int folder) {
        try {
            switch (folder) {
                case QQMusicController.FOLDER_FAVOURITE:
                    XposedHelpers.callMethod(tool, "playFavourMusic");
                    return true;
                case QQMusicController.FOLDER_PERSONAL_RADIO:
                    XposedHelpers.callMethod(tool, "playRandom");
                    return true;
                case QQMusicController.FOLDER_DAILY_30:
                    playSentinel(cl, tool, TXZHook.SENTINEL_DAILY30);
                    return true;
                case QQMusicController.FOLDER_RANK:
                    playSentinel(cl, tool, TXZHook.SENTINEL_RANK);
                    return true;
                default:
                    LogManager.w(TAG, "场景拦截: 未知歌单 type=" + folder);
                    return false;
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "场景拦截: 派发歌单失败 type=" + folder, t);
            return false;
        }
    }

    /** 排行榜/每日30首：伪造哨兵 MusicModel 走代理 playMusic 的歌单分支 */
    private static void playSentinel(ClassLoader cl, Object tool, String sentinel)
            throws Exception {
        Class<?> modelCls = XposedHelpers.findClass(MUSIC_MODEL, cl);
        Object model = modelCls.newInstance();
        XposedHelpers.callMethod(model, "setTitle", sentinel);
        XposedHelpers.callMethod(model, "setArtist", (Object) new String[0]);
        XposedHelpers.callMethod(model, "setAlbum", "");
        try {
            XposedHelpers.callMethod(model, "setKeywords", (Object) new String[0]);
        } catch (Throwable ignored) {
            // 旧版 SDK 无 setKeywords 时忽略
        }
        XposedHelpers.callMethod(tool, "playMusic", model);
    }

    /** 尽力 TTS 播报（方法不存在/失败均不影响播放） */
    private static void speak(ClassLoader cl, int folder) {
        try {
            Object rm = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(RESOURCE_MANAGER, cl), "getInstance");
            String text;
            switch (folder) {
                case QQMusicController.FOLDER_FAVOURITE:
                    text = "好的，为您播放我喜欢的歌";
                    break;
                case QQMusicController.FOLDER_PERSONAL_RADIO:
                    text = "好的，为您播放猜你喜欢";
                    break;
                case QQMusicController.FOLDER_DAILY_30:
                    text = "好的，为您播放每日推荐";
                    break;
                case QQMusicController.FOLDER_RANK:
                    text = "好的，为您播放排行榜";
                    break;
                default:
                    text = "好的，这就为您播放";
                    break;
            }
            XposedHelpers.callMethod(rm, "speakTextOnRecordWin", text, true, null);
        } catch (Throwable t) {
            LogManager.d(TAG, "场景拦截: TTS 播报跳过: " + t.getMessage());
        }
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part);
    }
}
