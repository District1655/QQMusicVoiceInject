package com.syu.voice.hook;

import android.util.Base64;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * QQ音乐官方第三方 AIDL API（ApiMethodsImpl）的进程内持有者。
 *
 * 反编译 QQ音乐HD_6.9.0.7 确认（com.tencent.qqmusiccar.third.api.apiImpl.ApiMethodsImpl，
 * pad 版继承自 car 版，类位于 classes3.dex）：
 *
 *   voicePlay(String query, List<String> slotList, IQQMusicApiCallback cb)
 *       —— QQ音乐自己的 AI 语音播放链路：搜索 query 并直接后台播放，不弹搜索框 UI。
 *   playMusic() / pauseMusic() / resumeMusic() / stopMusic()
 *   skipToNext() / skipToPrevious() / seekForward(long) / seekBack(long)
 *   playSongMid(List<String> midList, IQQMusicApiCallback cb)
 *   playSongMidAtIndex(List<String> midList, int index, IQQMusicApiCallback cb)
 *   search(String keyword, int searchType, boolean firstPage, IQQMusicApiCallback cb)
 *   getCurrentSong() / getPlaybackState() / getPlayMode() / setPlayMode(int)
 *
 * 实例获取：QQMusicApiService.onCreate -> 字段 e (QQMusicApiImpl) -> 字段 e (ApiMethodsImpl)。
 * 由 QQProcessHook 在 QQ音乐进程内 hook 捕获后 set() 进来。
 */
public final class ApiHolder {

    public static final String TAG = MainHook.TAG;

    /** ApiMethodsImpl 实例（QQ音乐进程内，主进程） */
    private static volatile Object sApi;
    private static volatile ClassLoader sCl;

    private ApiHolder() {
    }

    public static void init(ClassLoader cl) {
        sCl = cl;
    }

    public static boolean isReady() {
        return sApi != null;
    }

    /** 由 QQProcessHook 在捕获到 ApiMethodsImpl 实例后调用 */
    public static void set(Object api) {
        if (api == null || api == sApi) {
            return;
        }
        sApi = api;
        LogManager.i(TAG, "ApiMethodsImpl 实例已捕获: " + api.getClass().getName());
        // 顺便打印当前播放器状态，验证实例可用
        try {
            int state = (Integer) XposedHelpers.callMethod(api, "getPlaybackState");
            int mode = (Integer) XposedHelpers.callMethod(api, "getPlayMode");
            LogManager.i(TAG, "API 状态检查 -> playbackState=" + state + ", playMode=" + mode);
        } catch (Throwable t) {
            LogManager.d(TAG, "API 状态检查失败（可忽略）: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 点歌：后台搜索直接播放（核心）
    // ------------------------------------------------------------------

    /**
     * 用 QQ音乐 AI 语音链路后台搜索并直接播放。
     *
     * @return true 表示调用已发出（是否播放成功以日志为准）
     */
    public static boolean voicePlay(String query) {
        Object api = sApi;
        if (api == null) {
            LogManager.w(TAG, "voicePlay 失败：ApiMethodsImpl 未就绪");
            return false;
        }
        try {
            // v1.3.3：入口统一过识别纠错（覆盖广播/其他路径进入的 query）
            String fixed = SongCorrector.correctQuery(query);
            if (!fixed.equals(query)) {
                LogManager.i(TAG, "voicePlay 入口纠错: " + query + " -> " + fixed);
            }
            Object callback = makeCallback("voicePlay");
            List<String> slotList = new ArrayList<String>();
            XposedHelpers.callMethod(api, "voicePlay", fixed, slotList, callback);
            LogManager.i(TAG, "voicePlay 已发出 -> query=" + fixed);
            return true;
        } catch (Throwable t) {
            LogManager.e(TAG, "voicePlay 调用失败: " + query, t);
            return false;
        }
    }

    /**
     * 更可控的兜底：搜索 -> 取第一条 -> playSongMidAtIndex 直接播放。
     * 当 voicePlay 效果不佳时启用（预留，未接线）。
     */
    public static boolean searchAndPlayFirst(String keyword) {
        Object api = sApi;
        if (api == null) {
            return false;
        }
        try {
            Object callback = makeCallback("search");
            XposedHelpers.callMethod(api, "search", keyword, 0, true, callback);
            LogManager.i(TAG, "search 已发出 -> keyword=" + keyword);
            return true;
        } catch (Throwable t) {
            LogManager.e(TAG, "search 调用失败: " + keyword, t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    public static final int CMD_PLAY = 0;
    public static final int CMD_PAUSE = 1;
    public static final int CMD_PREV = 2;
    public static final int CMD_NEXT = 3;
    public static final int CMD_PLAY_MV = 4;
    public static final int CMD_FAV = 5;
    public static final int CMD_UNFAV = 6;
    public static final int CMD_SEEK_FORWARD = 7;
    public static final int CMD_SEEK_BACKWARD = 8;
    public static final int CMD_STOP = 9;
    public static final int CMD_MODE_SINGLE = 101;
    public static final int CMD_MODE_LIST = 103;
    public static final int CMD_MODE_SHUFFLE = 105;

    /** 播放控制：0播放 1暂停 2上一首 3下一首 9停止 7/8快进快退 101/103/105循环模式 */
    public static boolean control(int cmd, long extra) {
        Object api = sApi;
        if (api == null) {
            LogManager.w(TAG, "control(" + cmd + ") 失败：ApiMethodsImpl 未就绪");
            return false;
        }
        try {
            switch (cmd) {
                case CMD_PLAY:
                    XposedHelpers.callMethod(api, "playMusic");
                    break;
                case CMD_PAUSE:
                    XposedHelpers.callMethod(api, "pauseMusic");
                    break;
                case CMD_PREV:
                    XposedHelpers.callMethod(api, "skipToPrevious");
                    break;
                case CMD_NEXT:
                    XposedHelpers.callMethod(api, "skipToNext");
                    break;
                case CMD_STOP:
                    XposedHelpers.callMethod(api, "stopMusic");
                    break;
                case CMD_SEEK_FORWARD:
                    XposedHelpers.callMethod(api, "seekForward", extra);
                    break;
                case CMD_SEEK_BACKWARD:
                    XposedHelpers.callMethod(api, "seekBack", extra);
                    break;
                case CMD_MODE_SINGLE:
                case CMD_MODE_LIST:
                case CMD_MODE_SHUFFLE:
                    XposedHelpers.callMethod(api, "setPlayMode", cmd);
                    break;
                default:
                    LogManager.w(TAG, "control: 未知命令 " + cmd);
                    return false;
            }
            LogManager.i(TAG, "control 已执行 -> cmd=" + cmd + ", extra=" + extra);
            return true;
        } catch (Throwable t) {
            LogManager.e(TAG, "control(" + cmd + ") 调用失败", t);
            return false;
        }
    }

    /** 解析广播里的 search_key：优先 Base64（URL_SAFE/标准），失败按明文处理 */
    public static String decodeSearchKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        // 尝试 URL_SAFE Base64（新模块使用）
        String decoded = tryBase64(trimmed, Base64.URL_SAFE | Base64.NO_WRAP);
        if (decoded != null) {
            return decoded;
        }
        // 尝试标准 Base64（兼容旧模块）
        decoded = tryBase64(trimmed, Base64.NO_WRAP);
        if (decoded != null) {
            return decoded;
        }
        // 无法解码 -> 视为明文
        return trimmed;
    }

    private static String tryBase64(String s, int flags) {
        try {
            byte[] data = Base64.decode(s, flags);
            if (data == null || data.length == 0) {
                return null;
            }
            String decoded = new String(data, "UTF-8");
            if (looksReadable(decoded)) {
                return decoded;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 判定解码结果是否像正常文本（含中文或常见可打印字符） */
    private static boolean looksReadable(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            // 中文范围或可打印 ASCII 或常见全角字符
            if ((c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0x20 && c < 0x7F)
                    || c >= 0xFF00) {
                printable++;
            }
        }
        return printable * 10 >= s.length() * 9;
    }

    // ------------------------------------------------------------------
    // IQQMusicApiCallback 动态代理
    // ------------------------------------------------------------------

    /** 动态实现 IQQMusicApiCallback AIDL 接口，避免 AIDL 类无法编译期引用 */
    private static Object makeCallback(final String desc) {
        if (sCl == null) {
            return null;
        }
        try {
            Class<?> cbCls = XposedHelpers.findClass(
                    "com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback", sCl);
            return Proxy.newProxyInstance(sCl, new Class<?>[]{cbCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("onSuccess".equals(name) && args != null && args.length > 0) {
                        Object bundle = args[0];
                        String summary = dumpBundle(bundle);
                        LogManager.i(TAG, "[" + desc + "] onSuccess bundle: " + summary);
                    } else if ("onError".equals(name)) {
                        LogManager.w(TAG, "[" + desc + "] onError code="
                                + (args != null && args.length > 0 ? args[0] : "?")
                                + " msg=" + (args != null && args.length > 1 ? args[1] : "?"));
                    } else {
                        LogManager.d(TAG, "[" + desc + "] 回调 " + name);
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (ret == int.class) {
                        return Integer.valueOf(0);
                    }
                    if (ret == long.class) {
                        return Long.valueOf(0L);
                    }
                    if (ret == void.class) {
                        return null;
                    }
                    return null;
                }
            });
        } catch (Throwable t) {
            LogManager.d(TAG, "makeCallback 失败（可忽略，传 null）: " + t.getMessage());
            return null;
        }
    }

    private static String dumpBundle(Object bundle) {
        try {
            if (!(bundle instanceof android.os.Bundle)) {
                return String.valueOf(bundle);
            }
            android.os.Bundle b = (android.os.Bundle) bundle;
            StringBuilder sb = new StringBuilder();
            for (String k : b.keySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                Object v = b.get(k);
                String vs = String.valueOf(v);
                if (vs.length() > 120) {
                    vs = vs.substring(0, 120) + "...";
                }
                sb.append(k).append('=').append(vs);
            }
            return sb.length() == 0 ? "(empty)" : sb.toString();
        } catch (Throwable t) {
            return "dump失败:" + t.getMessage();
        }
    }
}
