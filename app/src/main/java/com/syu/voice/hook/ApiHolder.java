package com.syu.voice.hook;

import android.util.Base64;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
            // v1.5.0（自 v1.4.4 移植）：携带语义槽。有槽即 SearchSong 意图按槽搜索，
            // 不靠 QQ NLU 自由解析（无槽时"毛不易"等歌手会被猜错、播错歌）。
            List<String> slotList = SongCorrector.buildSlots(fixed);
            if (!slotList.isEmpty()) {
                LogManager.i(TAG, "voicePlay 语义槽: " + slotList + " <- query=" + fixed);
            }
            Object callback = makeCallback("voicePlay");
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

    // ------------------------------------------------------------------
    // 歌单/电台播放（v1.7.0 新增）
    //
    // 反编译 QQ音乐HD_6.9.0.7 确认 ApiMethodsImpl.playFolderType(folderId, type, index, cb)
    // 服务端 ThirdApiDataSourceBridge.playFolderType 仅处理两种 type：
    //   201 -> playFavourite：播放"我喜欢/收藏"的歌曲（需登录 QQ音乐，未登录返回 code=7）
    //   104 -> ControlForThird.f()：播放个人电台（QQ音乐智能推荐流，"随便听听/推荐歌单"语义）
    // folderId 服务端不使用（仅非空校验），按官方 ThirdApiDataSource 的 id 格式 "type|sub" 传。
    // ------------------------------------------------------------------

    public static final int FOLDER_FAVOURITE = 201;
    public static final int FOLDER_PERSONAL_RADIO = 104;
    /** v1.7.1：每日30首（getSongList type=108，folderId 传 "0"，服务端映射 "202|0"） */
    public static final int FOLDER_DAILY_30 = 108;
    /** v1.7.1：排行榜（getFolderList type=2 取首个榜单 → getSongList type=102） */
    public static final int FOLDER_RANK = 2;

    /**
     * 歌单指令统一入口（v1.7.1）：action=30 的 m0 即 cmd。
     * 201/104 走官方 playFolderType 直放；108/2 走"取列表 mid → playSongMid"链路。
     */
    public static boolean playFolderCmd(int cmd) {
        switch (cmd) {
            case FOLDER_FAVOURITE:
                return playFolder(FOLDER_FAVOURITE);
            case FOLDER_PERSONAL_RADIO:
                return playFolder(FOLDER_PERSONAL_RADIO);
            case FOLDER_DAILY_30:
                return playFolderSongs(FOLDER_DAILY_30, "0", "每日30首");
            case FOLDER_RANK:
                return playRank();
            default:
                LogManager.w(TAG, "playFolderCmd: 未知歌单指令 " + cmd);
                return false;
        }
    }

    /** 播放歌单/电台：201=我喜欢/收藏歌曲，104=猜你喜欢（个人电台推荐流） */
    public static boolean playFolder(int folderType) {
        Object api = sApi;
        if (api == null) {
            LogManager.w(TAG, "playFolder(" + folderType + ") 失败：ApiMethodsImpl 未就绪");
            return false;
        }
        try {
            String folderId = folderType + "|0";
            Object callback = makeCallback("playFolder(" + folderType + ")");
            XposedHelpers.callMethod(api, "playFolderType", folderId, folderType, 0, callback);
            LogManager.i(TAG, "playFolderType 已发出 -> type=" + folderType
                    + (folderType == FOLDER_FAVOURITE ? "（我喜欢/收藏）"
                    : folderType == FOLDER_PERSONAL_RADIO ? "（个人电台/推荐）" : ""));
            return true;
        } catch (Throwable t) {
            LogManager.e(TAG, "playFolder(" + folderType + ") 调用失败", t);
            return false;
        }
    }

    /**
     * 解析广播里的 search_key。发送端（v1.6.2+）= 标准 Base64.NO_WRAP 再 URLEncoder，
     * getQueryParameter 后拿到的就是标准 Base64（含 '+' '/' '='）。
     * 尝试顺序：
     *   1) 标准 Base64（当前发送端格式）
     *   2) 空格还原为 '+' 后标准解码（兼容 v1.6.2 以前旧模块：URL 里 '+' 被 form-urlencoded 解析成空格）
     *   3) URL_SAFE Base64
     *   4) 均失败 → 视为明文
     */
    public static String decodeSearchKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String decoded;
        // 1) 含空格优先自愈：旧版（v1.6.2 前）广播里 '+' 被 form-urlencoded 解析成空格，
        //    base64 字母表不含空格——出现空格必是 '+' 被误解析，先还原再标准解码。
        //    （注意：Android Base64.decode 会直接跳过空格解出乱码，所以必须先于直接解码尝试）
        if (trimmed.indexOf(' ') >= 0) {
            decoded = tryBase64(trimmed.replace(' ', '+'), Base64.NO_WRAP);
            if (decoded != null) {
                LogManager.i(TAG, "search_key 含空格，按旧版 URL 解析残留还原 '+' 后解码成功");
                return decoded;
            }
        }
        // 2) 标准 Base64（v1.6.2+ 发送端格式：NO_WRAP 再 URLEncoder，getQueryParameter 后即标准 b64）
        decoded = tryBase64(trimmed, Base64.NO_WRAP);
        if (decoded != null) {
            return decoded;
        }
        // 3) URL_SAFE Base64
        decoded = tryBase64(trimmed, Base64.URL_SAFE | Base64.NO_WRAP);
        if (decoded != null) {
            return decoded;
        }
        // 4) 无法解码 -> 视为明文
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

    // ------------------------------------------------------------------
    // 多歌单播放（v1.7.1）：每日30首、排行榜
    //
    // 官方 AIDL 能力（ThirdApiDataSourceBridge$C04481 实测分发）：
    //   getSongList(folderId, type, page)：
    //     type=108 folderId="0" -> 服务端映射 "202|0" -> getFolderSongList（每日30首/百万收藏等推荐文件夹）
    //     type=102 folderId=榜单id -> getRankSongList（排行榜歌曲）
    //     type=201 -> 我喜欢列表；type=202 -> 最近播放；type=104 -> 猜你喜欢电台当前页
    //   getFolderList(folderId, type, page)：
    //     type=2 -> getRankList（排行榜文件夹列表，FolderInfo.id 即榜单 id，mainTitle 即榜单名）
    //   成功回调 Bundle：code=0, data=Data.Song/Data.FolderInfo 列表的 gson JSON，hasMore
    //   拿到 mid 列表后调 playSongMid(mids, cb) 整列表播放。
    // ------------------------------------------------------------------

    /** 取歌单歌曲 mid 列表并整列表播放（每日30首 type=108 等） */
    public static boolean playFolderSongs(final int type, final String folderId, final String desc) {
        Object api = sApi;
        if (api == null) {
            LogManager.w(TAG, desc + "：ApiMethodsImpl 未就绪");
            return false;
        }
        try {
            Object cb = makeSongListCallback(desc);
            XposedHelpers.callMethod(api, "getSongList", folderId, type, 0, cb);
            LogManager.i(TAG, "getSongList 已发出 -> " + desc + " type=" + type + " folderId=" + folderId);
            return true;
        } catch (Throwable t) {
            LogManager.e(TAG, desc + " getSongList 调用失败", t);
            return false;
        }
    }

    /** 排行榜：先取榜单列表，选第一个（官方默认序，通常为热门榜），再取歌曲播放 */
    public static boolean playRank() {
        Object api = sApi;
        if (api == null) {
            LogManager.w(TAG, "排行榜：ApiMethodsImpl 未就绪");
            return false;
        }
        try {
            Object cb = makeRankFolderCallback();
            XposedHelpers.callMethod(api, "getFolderList", "0", FOLDER_RANK, 0, cb);
            LogManager.i(TAG, "getFolderList 已发出 -> 排行榜(type=2)");
            return true;
        } catch (Throwable t) {
            LogManager.e(TAG, "排行榜 getFolderList 调用失败", t);
            return false;
        }
    }

    /** 从 getSongList 成功 Bundle（data = Data.Song 列表 JSON）提取歌曲 mid */
    private static java.util.List<String> extractMids(android.os.Bundle b) {
        java.util.List<String> mids = new java.util.ArrayList<String>();
        if (b == null) {
            return mids;
        }
        try {
            String data = b.getString("data");
            if (data == null || data.isEmpty() || "null".equals(data)) {
                return mids;
            }
            org.json.JSONArray arr = new org.json.JSONArray(data);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String mid = o.optString("mid", null);
                if (mid != null && !mid.isEmpty() && !"null".equals(mid)) {
                    mids.add(mid);
                }
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "extractMids 解析失败", t);
        }
        return mids;
    }

    /** 用 mid 列表整列表播放 */
    private static void playMids(java.util.List<String> mids, String desc) {
        if (mids == null || mids.isEmpty()) {
            LogManager.w(TAG, desc + "：歌曲 mid 列表为空，放弃播放");
            return;
        }
        Object api = sApi;
        if (api == null) {
            LogManager.w(TAG, desc + "：ApiMethodsImpl 未就绪，无法 playSongMid");
            return;
        }
        try {
            Object cb = makeCallback(desc + " playSongMid");
            XposedHelpers.callMethod(api, "playSongMid", new java.util.ArrayList<String>(mids), cb);
            LogManager.i(TAG, desc + " -> playSongMid 已发出，共 " + mids.size() + " 首");
        } catch (Throwable t) {
            LogManager.e(TAG, desc + " playSongMid 调用失败", t);
        }
    }

    /** getSongList 回调：成功取 mid 列表 → playSongMid；失败落日志 */
    private static Object makeSongListCallback(final String desc) {
        if (sCl == null) {
            return null;
        }
        try {
            Class<?> cbCls = XposedHelpers.findClass(
                    "com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback", sCl);
            return Proxy.newProxyInstance(sCl, new Class<?>[]{cbCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    try {
                        if ("onSuccess".equals(name) && args != null && args.length > 0
                                && args[0] instanceof android.os.Bundle) {
                            java.util.List<String> mids = extractMids((android.os.Bundle) args[0]);
                            LogManager.i(TAG, "[" + desc + "] onSuccess：取到 " + mids.size() + " 首");
                            playMids(mids, desc);
                        } else if ("onError".equals(name)) {
                            LogManager.w(TAG, "[" + desc + "] onError code="
                                    + (args != null && args.length > 0 ? args[0] : "?")
                                    + " msg=" + (args != null && args.length > 1 ? args[1] : "?"));
                        }
                    } catch (Throwable t) {
                        LogManager.e(TAG, "[" + desc + "] 回调处理异常", t);
                    }
                    return defaultReturnValue(method);
                }
            });
        } catch (Throwable t) {
            LogManager.e(TAG, "makeSongListCallback 失败", t);
            return null;
        }
    }

    /** getFolderList(排行榜) 回调：取第一个榜单 id → getSongList(type=102) → 播放 */
    private static Object makeRankFolderCallback() {
        if (sCl == null) {
            return null;
        }
        try {
            Class<?> cbCls = XposedHelpers.findClass(
                    "com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback", sCl);
            return Proxy.newProxyInstance(sCl, new Class<?>[]{cbCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    try {
                        if ("onSuccess".equals(name) && args != null && args.length > 0
                                && args[0] instanceof android.os.Bundle) {
                            android.os.Bundle b = (android.os.Bundle) args[0];
                            String data = b.getString("data");
                            LogManager.i(TAG, "[排行榜] onSuccess 榜单列表: "
                                    + (data != null && data.length() > 150 ? data.substring(0, 150) + "..." : data));
                            String rankId = null;
                            String rankTitle = null;
                            if (data != null) {
                                org.json.JSONArray arr = new org.json.JSONArray(data);
                                for (int i = 0; i < arr.length(); i++) {
                                    org.json.JSONObject o = arr.optJSONObject(i);
                                    if (o == null) {
                                        continue;
                                    }
                                    String id = o.optString("id", null);
                                    if (id != null && !id.isEmpty() && !"null".equals(id)) {
                                        rankId = id;
                                        rankTitle = o.optString("mainTitle", "排行榜");
                                        break;
                                    }
                                }
                            }
                            if (rankId == null) {
                                LogManager.w(TAG, "[排行榜] 榜单列表为空或无 id");
                            } else {
                                LogManager.i(TAG, "[排行榜] 选中首个榜单：" + rankTitle + " id=" + rankId);
                                playFolderSongs(102, rankId, "排行榜·" + rankTitle);
                            }
                        } else if ("onError".equals(name)) {
                            LogManager.w(TAG, "[排行榜] onError code="
                                    + (args != null && args.length > 0 ? args[0] : "?")
                                    + " msg=" + (args != null && args.length > 1 ? args[1] : "?"));
                        }
                    } catch (Throwable t) {
                        LogManager.e(TAG, "[排行榜] 回调处理异常", t);
                    }
                    return defaultReturnValue(method);
                }
            });
        } catch (Throwable t) {
            LogManager.e(TAG, "makeRankFolderCallback 失败", t);
            return null;
        }
    }

    private static Object defaultReturnValue(Method method) {
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
        return null;
    }
}
