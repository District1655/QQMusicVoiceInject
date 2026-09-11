package com.syu.voice.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * v1.8.11：车助理进程内的场景级歌单兜底拦截。
 *
 * 背景（210958 日志实证）：本机音乐域 NLU 在车助理进程内完成，
 * VoiceAdapter.initSenceTool() 注册的 SceneTool 链（匿名内部类 $17/$18）
 * 收到云端场景 JSON，例如：
 *   {"action":"play","scene":"music",
 *    "model":{"keywords":["收藏"]},"text":"播放收藏的歌吧","t":5}
 * $17 只对白名单内置播放器（com.syu.music/酷我/酷狗/wecarflow）处理，
 * $18 对 qqmusicpad 也返回 false，链路最终经 TXZ IPC 以
 * MusicModel(title=null, keywords=["收藏"]) 调代理 playMusic——
 * v1.8.10 及之前代理不读 keywords，歌单意图丢失，只剩"恢复播放"。
 *
 * 策略：在 $17/$18.process(SceneType, String) 的 before 回调里解析
 * 原始 ASR 文本 + model.title + model.keywords，命中歌单关键词且当前
 * 注册的 MusicTool 是本模块代理时，直接调用代理的
 * playFavourMusic/playRandom/哨兵 playMusic 并 setResult(true) 截断
 * 场景链，不再依赖云端 NLU 是否区分"收藏/我喜欢/猜你喜欢/排行榜"。
 * 非本模块代理（内置播放器）一律放行，避免误伤。
 *
 * 匿名内部类编号随车助理 APK 版本可能变化，各目标类独立 try/catch，
 * 找不到只告警不影响其他 hook；这是代理层 keywords 识别之外的第二道保险。
 */
public final class ScenePlaylistHook {

    public static final String TAG = MainHook.TAG;

    private static final String MUSIC_MANAGER = "com.txznet.sdk.TXZMusicManager";
    private static final String MUSIC_TOOL = "com.txznet.sdk.TXZMusicManager$MusicTool";
    private static final String MUSIC_MODEL = "com.txznet.sdk.TXZMusicManager$MusicModel";
    private static final String SCENE_TYPE = "com.txznet.sdk.TXZSceneManager$SceneType";
    private static final String RESOURCE_MANAGER = "com.txznet.sdk.TXZResourceManager";

    private static final String[] SCENE_TOOL_CLASSES = {
            "com.syu.voice.VoiceAdapter$17",
            "com.syu.voice.VoiceAdapter$18",
    };

    /** $17/$18 会以同一 JSON 先后回调，用于去重 */
    private static volatile String sLastKey = "";
    private static volatile long sLastMs = 0L;

    private ScenePlaylistHook() {
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
        if (!"music".equals(job.optString("scene", ""))) {
            return;
        }
        // playFavourMusic/playRandom/pause 等云端已正确分类的 action 放行，
        // 只兜底最泛的 "play"
        if (!"play".equals(job.optString("action", ""))) {
            return;
        }

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
        String raw = combined.toString().trim();
        if (raw.isEmpty()) {
            return;
        }

        final int folder = QQMusicToolProxy.matchPlaylist(raw);
        if (folder < 0) {
            return; // 非歌单话术（普通点歌/歌手），放行原链路
        }

        String key = raw + "|" + folder;
        long now = System.currentTimeMillis();
        if (key.equals(sLastKey) && now - sLastMs < 2500L) {
            LogManager.d(TAG, "场景拦截: $17/$18 重复回调，去重截断: " + key);
            param.setResult(Boolean.TRUE);
            return;
        }

        Object tool = findRegisteredTool(cl);
        if (!isOurProxy(tool)) {
            // 当前音乐源不是本模块代理（内置播放器或尚未注册）：放行原链路，
            // 代理层 QQMusicToolProxy 自身的 keywords 识别仍会兜底
            LogManager.d(TAG, "场景拦截: 命中 type=" + folder
                    + " 但当前 MusicTool 非本模块代理，放行");
            sLastKey = key;
            sLastMs = now;
            return;
        }

        LogManager.i(TAG, "场景拦截命中歌单 type=" + folder + "（匹配文本=" + raw
                + "），直接路由并截断车助理场景链");
        boolean ok = dispatch(cl, tool, folder);
        if (ok) {
            sLastKey = key;
            sLastMs = now;
            speak(cl, folder);
            param.setResult(Boolean.TRUE);
        }
    }

    /** 从 TXZMusicManager 实例字段中找到已注册的 MusicTool（字段名混淆，按类型扫描） */
    private static Object findRegisteredTool(ClassLoader cl) {
        try {
            Object mgr = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MUSIC_MANAGER, cl), "getInstance");
            if (mgr == null) {
                return null;
            }
            for (Field f : mgr.getClass().getDeclaredFields()) {
                if (MUSIC_TOOL.equals(f.getType().getName())) {
                    f.setAccessible(true);
                    Object tool = f.get(mgr);
                    if (tool != null) {
                        return tool;
                    }
                }
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "场景拦截: 查找已注册 MusicTool 失败", t);
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
