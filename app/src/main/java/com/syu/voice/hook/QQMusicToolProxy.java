package com.syu.voice.hook;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * 基于 MediaSession 的通用音乐工具（MusicTool 接口的动态代理）。
 *
 * 反编译确认 com.txznet.sdk.TXZMusicManager$MusicTool 接口方法：
 *   continuePlay / exit / favourMusic / getCurrentMusicModel / isPlaying /
 *   next / pause / play / playFavourMusic / playMusic(MusicModel) /
 *   playRandom / prev / setStatusListener / switchModeLoopAll /
 *   switchModeLoopOne / switchModeRandom / switchSong / unfavourMusic
 *
 * 控制通道：MediaSessionManager.getActiveSessions -> 匹配目标包名的
 * MediaController -> TransportControls（play/pause/skipToNext/...）。
 * QQ 音乐车机版/HD 均实现 MediaSession，可被该系统级应用（android.uid.system）
 * 接管控制。
 */
public final class QQMusicToolProxy {

    public static final String TAG = MainHook.TAG;

    private static final String IFACE = "com.txznet.sdk.TXZMusicManager$MusicTool";
    private static final String MODEL = "com.txznet.sdk.TXZMusicManager$MusicModel";
    private static final String STATUS_LISTENER = "com.txznet.sdk.TXZMusicManager$MusicToolStatusListener";

    /**
     * 歌单关键词识别（可接收 title / keywords / ASR 原文任意文本）。
     * 云端 NLU 对歌单话术有两种下发：title 误带点歌词（v1.8.1 起兜底），
     * 或放在 model.keywords 数组而 title 为空（如 keywords=["收藏"]，v1.8.11 新增）。
     * 命中返回 QQMusicController.FOLDER_*，未命中返回 -1 走正常搜索/恢复播放。
     */
    public static int matchPlaylist(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return -1;
        }
        // 排行榜
        if (t.contains("排行榜") || t.contains("榜单") || t.contains("排行")
                || t.contains("热歌榜") || t.contains("新歌榜") || t.contains("飙升榜")
                || t.contains("巅峰榜") || t.contains("音乐榜") || t.contains("流行榜")
                || (t.length() >= 3 && t.endsWith("榜"))) {
            return QQMusicController.FOLDER_RANK;
        }
        // 每日30首/每日推荐（含 ASR 错字："三零"="30"）
        if (t.contains("每日30") || t.contains("每日三十") || t.contains("每天30")
                || t.contains("每天三十") || t.contains("每日推荐") || t.contains("每天推荐")
                || t.contains("三零") || t.contains("30首")) {
            return QQMusicController.FOLDER_DAILY_30;
        }
        // 猜你喜欢/随便听听/推荐歌曲（个人电台）——先于"我喜欢"规则，避免误吞"猜你喜欢"
        if (t.contains("猜你喜欢") || t.contains("随便听")
                || t.contains("好听的") || t.contains("来点歌")
                || (t.contains("推荐")
                    && (t.contains("歌") || t.contains("音乐") || t.contains("曲")))) {
            return QQMusicController.FOLDER_PERSONAL_RADIO;
        }
        // 我喜欢/收藏（含 ASR 错字"歌丹"：含"收藏"即命中；
        // "你喜欢"="我喜欢"的同音误识别；
        // 以"喜欢"结尾且前面还有内容也视为收藏（实测 ASR 出"china喜欢"）；
        // 单用"喜欢"（length=2）可能是点歌《喜欢》，不走此规则）
        if (t.contains("收藏") || t.contains("红心")
                || t.contains("我喜欢") || t.contains("我的喜欢") || t.contains("你喜欢")
                || t.contains("喜欢的歌") || t.contains("喜欢的音乐")
                || (t.length() > 2 && t.endsWith("喜欢"))) {
            return QQMusicController.FOLDER_FAVOURITE;
        }
        return -1;
    }

    /** 创建 MusicTool 接口的动态代理对象 */
    public static Object create(ClassLoader cl, String pkg) throws Throwable {
        Class<?> iface = XposedHelpers.findClass(IFACE, cl);
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new Handler(cl, pkg));
    }

    static final class Handler implements InvocationHandler {

        private final ClassLoader mCl;
        private final String mPkg;
        private volatile Object mStatusListener;
        private volatile QQMusicController mQQController;

        Handler(ClassLoader cl, String pkg) {
            this.mCl = cl;
            this.mPkg = pkg;
        }

        /** QQ音乐车机版/HD版优先用原生 scheme 控制（不依赖 MediaSession） */
        private boolean useQQController() {
            return mPkg.equals("com.tencent.qqmusiccar")
                    || mPkg.equals("com.tencent.qqmusicpad");
        }

        private QQMusicController qq() {
            if (mQQController == null) {
                Context ctx = ContextHolder.get();
                if (ctx != null) {
                    mQQController = new QQMusicController(ctx, mPkg);
                }
            }
            return mQQController;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            final String name = method.getName();
            // 全方法调用日志：用于排查车助理点播时实际调用的方法名和参数
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(mPkg).append("] MusicTool.").append(name).append("(");
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(", ");
                    Object a = args[i];
                    if (a == null) {
                        sb.append("null");
                    } else {
                        sb.append(a.getClass().getSimpleName()).append(":").append(describeModel(a));
                    }
                }
            }
            sb.append(")");
            LogManager.i(TAG, sb.toString());
            try {
                switch (name) {
                    case "setStatusListener":
                        mStatusListener = (args != null && args.length > 0) ? args[0] : null;
                        LogManager.d(TAG, "[" + mPkg + "] setStatusListener -> " + mStatusListener);
                        return null;

                    case "isPlaying":
                        boolean playing = isPlaying();
                        LogManager.d(TAG, "[" + mPkg + "] isPlaying -> " + playing);
                        return playing;                    case "play":
                    case "continuePlay":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_PLAY, 100);
                        } else {
                            transport().play();
                        }
                        notifyStatus(1); // STATE_START_PLAY
                        return null;

                    case "pause":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_PAUSE, 100);
                        } else {
                            transport().pause();
                        }
                        notifyStatus(2); // STATE_PAUSE_PLAY
                        return null;

                    case "next":
                    case "switchSong":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_NEXT, 100);
                        } else {
                            transport().skipToNext();
                        }
                        notifyStatus(4); // STATE_SONG_CHANGE
                        return null;

                    case "prev":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_PREV, 100);
                        } else {
                            transport().skipToPrevious();
                        }
                        notifyStatus(4); // STATE_SONG_CHANGE
                        return null;

                    case "exit":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: exit");
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_PAUSE, 100);
                        } else {
                            transport().pause();
                        }
                        return null;

                    case "playRandom":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: playRandom（随便听听/推荐）");
                        if (useQQController() && qq() != null) {
                            // v1.7.0：QQ音乐个人电台（智能推荐流）即"随便听听"，
                            // 比"切随机模式播当前列表"更贴合语音语义且不依赖已有列表
                            qq().playFolder(QQMusicController.FOLDER_PERSONAL_RADIO);
                        } else {
                            transport().playFromMediaId("__random__", null);
                        }
                        notifyStatus(1); // STATE_START_PLAY
                        return null;

                    case "playMusic":
                        Object pmModel = args != null && args.length > 0 ? args[0] : null;
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: playMusic " + describeModel(pmModel));
                        if (useQQController() && qq() != null) {
                            // v1.7.1：TXZHook 伪造的哨兵 title -> 直接播指定歌单，不走搜索
                            String title = modelTitle(pmModel);
                            if (TXZHook.SENTINEL_DAILY30.equals(title)) {
                                LogManager.i(TAG, "[" + mPkg + "] 哨兵路由 -> 每日30首");
                                qq().playFolder(QQMusicController.FOLDER_DAILY_30);
                            } else if (TXZHook.SENTINEL_RANK.equals(title)) {
                                LogManager.i(TAG, "[" + mPkg + "] 哨兵路由 -> 排行榜");
                                qq().playFolder(QQMusicController.FOLDER_RANK);
                            } else {
                                // v1.8.1：关键词兜底——TXZ 进程未注入新版模块（或云端 NLU 未拦截）
                                // 时，"播放我喜欢/收藏的歌单/排行榜"会被云 NLU 误判成点歌 title。
                                // 车助理进程每次升级都必然重新加载，在此按 title 二次识别并路由歌单。
                                // v1.8.11：云端还可能只下发 model.keywords（title=null），
                                // 把 keywords 一并纳入歌单匹配与搜索词。
                                String raw = modelRawText(pmModel);
                                int folder = matchPlaylist(raw);
                                if (folder >= 0) {
                                    LogManager.i(TAG, "[" + mPkg + "] 关键词路由 -> 歌单 type="
                                            + folder + "（匹配文本=" + raw + "）");
                                    qq().playFolder(folder);
                                } else {
                                    qq().searchAndPlay(extractQuery(pmModel));
                                }
                            }
                        } else {
                            playMusic(pmModel);
                        }
                        notifyStatus(1); // STATE_START_PLAY
                        return null;

                    case "getCurrentMusicModel":
                        Object model = buildMusicModel();
                        LogManager.d(TAG, "[" + mPkg + "] getCurrentMusicModel -> " + describeModel(model));
                        return model;

                    case "playFavourMusic":
                        // v1.7.0：播放"我喜欢/收藏"的歌曲 -> QQ音乐 playFolderType(201)
                        // （需在 QQ音乐HD 登录账号，未登录时 QQ 回调 onError code=7）
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: playFavourMusic（播放收藏）");
                        if (useQQController() && qq() != null) {
                            qq().playFolder(QQMusicController.FOLDER_FAVOURITE);
                        } else {
                            transport().play();
                        }
                        notifyStatus(1); // STATE_START_PLAY
                        return null;

                    case "favourMusic":
                        // 收藏当前播放歌曲：QQ音乐原生广播 m0=5 即支持
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: favourMusic（收藏当前歌曲）");
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_FAV, 100);
                        }
                        return null;

                    case "unfavourMusic":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: unfavourMusic（取消收藏当前歌曲）");
                        if (useQQController() && qq() != null) {
                            qq().controlPlay(QQMusicController.CTRL_UNFAV, 100);
                        }
                        return null;

                    case "switchModeLoopAll":
                    case "switchModeLoopOne":
                    case "switchModeRandom":
                        LogManager.w(TAG, "[" + mPkg + "] " + name + " 无公开AIDL，空实现");
                        return null;

                    default:
                        LogManager.d(TAG, "[" + mPkg + "] 未处理: " + name);
                        return null;
                }
            } catch (Throwable t) {
                LogManager.e(TAG, "invoke(" + name + ") 失败: " + t.getMessage(), t);
                return null;
            }
        }

        private static String describeModel(Object model) {
            if (model == null) {
                return "null";
            }
            try {
                Object title = XposedHelpers.callMethod(model, "getTitle");
                Object artistArr = XposedHelpers.callMethod(model, "getArtist");
                String artist = (artistArr instanceof String[] && ((String[]) artistArr).length > 0)
                        ? ((String[]) artistArr)[0] : "";
                String[] keywords = modelKeywords(model);
                String kw = keywords.length > 0 ? ", keywords=" + joinArr(keywords) : "";
                return "title=" + title + ", artist=" + artist + kw;
            } catch (Throwable t) {
                return String.valueOf(model);
            }
        }

        /** 反射读取 MusicModel.getKeywords()（String[]，读不到返回空数组） */
        private static String[] modelKeywords(Object musicModel) {
            if (musicModel == null) {
                return new String[0];
            }
            try {
                Object arr = XposedHelpers.callMethod(musicModel, "getKeywords");
                if (arr instanceof String[]) {
                    return (String[]) arr;
                }
            } catch (Throwable ignored) {
                // MusicModel 无 getKeywords 的旧版本 SDK 忽略
            }
            return new String[0];
        }

        private static String joinArr(String[] arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i]);
            }
            return sb.append("]").toString();
        }

        /**
         * v1.8.11：拼接 title + 首个 artist + 全部 keywords，
         * 供歌单关键词匹配（云端歌单话术可能只落在 keywords）。
         */
        private static String modelRawText(Object musicModel) {
            if (musicModel == null) {
                return "";
            }
            try {
                StringBuilder sb = new StringBuilder();
                Object title = XposedHelpers.callMethod(musicModel, "getTitle");
                if (title != null && !String.valueOf(title).isEmpty()) {
                    sb.append(title);
                }
                Object artistArr = XposedHelpers.callMethod(musicModel, "getArtist");
                if (artistArr instanceof String[]) {
                    for (String a : (String[]) artistArr) {
                        if (a != null && !a.isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(a);
                        }
                    }
                }
                for (String kw : modelKeywords(musicModel)) {
                    if (kw != null && !kw.isEmpty()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(kw);
                    }
                }
                return sb.toString();
            } catch (Throwable t) {
                return "";
            }
        }

        /** 读取 MusicModel 的 title（哨兵路由用） */
        private static String modelTitle(Object musicModel) {
            if (musicModel == null) {
                return "";
            }
            try {
                Object title = XposedHelpers.callMethod(musicModel, "getTitle");
                return title == null ? "" : String.valueOf(title);
            } catch (Throwable t) {
                return "";
            }
        }

        /** 从 MusicModel 提取搜索关键词（歌名 + 歌手 + keywords），供 QQ音乐 scheme 点歌使用 */
        private static String extractQuery(Object musicModel) {
            if (musicModel == null) {
                return "";
            }
            try {
                Object title = XposedHelpers.callMethod(musicModel, "getTitle");
                Object artistArr = XposedHelpers.callMethod(musicModel, "getArtist");
                StringBuilder query = new StringBuilder();
                if (title != null && !String.valueOf(title).isEmpty()) {
                    query.append(title);
                }
                if (artistArr instanceof String[] && ((String[]) artistArr).length > 0) {
                    if (query.length() > 0) {
                        query.append(' ');
                    }
                    query.append(((String[]) artistArr)[0]);
                }
                // v1.8.11：云端可能只把点歌词放在 keywords（title=null），
                // 如按歌手点播 keywords=["周杰伦"]
                for (String kw : modelKeywords(musicModel)) {
                    if (kw != null && !kw.isEmpty()) {
                        if (query.length() > 0) {
                            query.append(' ');
                        }
                        query.append(kw);
                    }
                }
                // v1.3.3：识别纠错（歌手/歌名同音错字 -> 正确词），防止错词直接进搜索
                String raw = query.toString();
                return SongCorrector.correctQuery(raw);
            } catch (Throwable t) {
                return "";
            }
        }

        // ------------------------------------------------------------------
        // MediaSession 控制
        // ------------------------------------------------------------------

        private MediaController controller() {
            try {
                MediaSessionManager msm = (MediaSessionManager) context()
                        .getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (msm == null) {
                    LogManager.w(TAG, "[" + mPkg + "] MediaSessionManager 为 null");
                    return null;
                }
                List<MediaController> sessions = msm.getActiveSessions(null);
                if (sessions == null || sessions.isEmpty()) {
                    LogManager.w(TAG, "[" + mPkg + "] 无活跃 MediaSession");
                    return null;
                }
                for (MediaController c : sessions) {
                    if (c != null && mPkg.equals(c.getPackageName())) {
                        return c;
                    }
                }
                LogManager.w(TAG, "[" + mPkg + "] 未找到匹配 Session，活跃包名: " + dumpPackages(sessions));
            } catch (Throwable t) {
                LogManager.e(TAG, "[" + mPkg + "] 获取 MediaSession 失败", t);
            }
            return null;
        }

        private static String dumpPackages(List<MediaController> sessions) {
            StringBuilder sb = new StringBuilder();
            for (MediaController c : sessions) {
                if (c != null) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(c.getPackageName());
                }
            }
            return sb.toString();
        }

        private MediaController.TransportControls transport() {
            MediaController c = controller();
            return c == null ? null : c.getTransportControls();
        }

        private boolean isPlaying() {
            // v1.3.2：QQ音乐HD 不暴露标准 MediaSession，直接查 MediaSession 永远 false，
            // 会导致 TXZ 判定"无播放上下文"并退出音乐场景，
            // 随后"上一曲/下一曲/暂停"等控制指令全被语义层拒绝（"没听清"）。
            // 改为 MediaSession 拿不到时保底返回 true，维持 TXZ 音乐场景激活。
            MediaController c = controller();
            if (c == null) {
                LogManager.d(TAG, "[" + mPkg + "] isPlaying: 无 MediaSession，保底 true（维持音乐场景）");
                return true;
            }
            PlaybackState ps = c.getPlaybackState();
            return ps != null
                    && ps.getState() == PlaybackState.STATE_PLAYING;
        }

        /** 语音点歌：把歌名+歌手转成 MediaSession 搜索请求 */
        private void playMusic(Object musicModel) {
            if (musicModel == null) {
                transport().play();
                return;
            }
            try {
                // v1.8.11：与 QQ 广播路径一致，keywords 也纳入搜索词
                String q = extractQuery(musicModel);
                LogManager.i(TAG, "[" + mPkg + "] playFromSearch: " + q);
                MediaController.TransportControls tc = transport();
                if (tc != null) {
                    tc.playFromSearch(q, new Bundle());
                } else {
                    LogManager.w(TAG, "[" + mPkg + "] playFromSearch 失败：无可用 TransportControls");
                }
            } catch (Throwable t) {
                LogManager.e(TAG, "[" + mPkg + "] playMusic 失败", t);
            }
        }

        /** 从当前播放元数据构建 TXZ MusicModel（title/artist/album/path） */
        private Object buildMusicModel() {
            try {
                MediaController c = controller();
                if (c == null) {
                    return null;
                }
                MediaMetadata md = c.getMetadata();
                if (md == null) {
                    LogManager.d(TAG, "[" + mPkg + "] getMetadata 为 null");
                    return null;
                }
                Class<?> modelClass = XposedHelpers.findClass(MODEL, mCl);
                Object model = XposedHelpers.newInstance(modelClass);
                String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                if (title != null) {
                    XposedHelpers.callMethod(model, "setTitle", title);
                }
                if (artist != null) {
                    XposedHelpers.callMethod(model, "setArtist", (Object) new String[]{artist});
                }
                if (album != null) {
                    XposedHelpers.callMethod(model, "setAlbum", album);
                }
                return model;
            } catch (Throwable t) {
                LogManager.e(TAG, "[" + mPkg + "] buildMusicModel 失败", t);
                return null;
            }
        }

        private Context context() {
            // 从 ContextHolder 取 Application Context（MainHook 在 Application.onCreate 时设置）
            // 不使用 ActivityThread.currentApplication() 反射，避免隐藏 API 反射失败导致 Context 为 null
            return ContextHolder.get();
        }

        // 状态上报：播放状态变化时通知 TXZ（MusicToolStatusListener.onStatusChange(int)），
        // 让 TXZ 音乐场景保持激活，否则"上一曲/下一曲/暂停"会被语义层过滤。
        // 常量：1=START_PLAY 2=PAUSE_PLAY 3=BUFFERING 4=SONG_CHANGE
        private void notifyStatus(int state) {
            Object l = mStatusListener;
            if (l == null) {
                LogManager.d(TAG, "[" + mPkg + "] 状态上报跳过（无 listener）state=" + state);
                return;
            }
            try {
                XposedHelpers.callMethod(l, "onStatusChange", state);
                LogManager.d(TAG, "[" + mPkg + "] 状态上报 onStatusChange(" + state + ")");
            } catch (Throwable t) {
                LogManager.e(TAG, "[" + mPkg + "] 状态上报失败 state=" + state + ": " + t.getMessage(), t);
            }
        }
    }
}
