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

    /** 创建 MusicTool 接口的动态代理对象 */
    public static Object create(ClassLoader cl, String pkg) throws Throwable {
        Class<?> iface = XposedHelpers.findClass(IFACE, cl);
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new Handler(cl, pkg));
    }

    static final class Handler implements InvocationHandler {

        private final ClassLoader mCl;
        private final String mPkg;
        private volatile Object mStatusListener;

        Handler(ClassLoader cl, String pkg) {
            this.mCl = cl;
            this.mPkg = pkg;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            final String name = method.getName();
            try {
                switch (name) {
                    case "setStatusListener":
                        mStatusListener = (args != null && args.length > 0) ? args[0] : null;
                        LogManager.d(TAG, "[" + mPkg + "] setStatusListener -> " + mStatusListener);
                        return null;

                    case "isPlaying":
                        boolean playing = isPlaying();
                        LogManager.d(TAG, "[" + mPkg + "] isPlaying -> " + playing);
                        return playing;

                    case "play":
                    case "continuePlay":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        transport().play();
                        return null;

                    case "pause":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        transport().pause();
                        return null;

                    case "next":
                    case "switchSong":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        transport().skipToNext();
                        return null;

                    case "prev":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: " + name);
                        transport().skipToPrevious();
                        return null;

                    case "exit":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: exit");
                        transport().pause();
                        return null;

                    case "playRandom":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: playRandom");
                        transport().playFromMediaId("__random__", null);
                        return null;

                    case "playMusic":
                        LogManager.i(TAG, "[" + mPkg + "] 语音指令: playMusic "
                                + describeModel(args != null && args.length > 0 ? args[0] : null));
                        playMusic(args != null && args.length > 0 ? args[0] : null);
                        return null;

                    case "getCurrentMusicModel":
                        Object model = buildMusicModel();
                        LogManager.d(TAG, "[" + mPkg + "] getCurrentMusicModel -> " + describeModel(model));
                        return model;

                    case "favourMusic":
                    case "unfavourMusic":
                    case "playFavourMusic":
                        LogManager.w(TAG, "[" + mPkg + "] " + name + " 无公开AIDL，空实现");
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
                return "title=" + title + ", artist=" + artist;
            } catch (Throwable t) {
                return String.valueOf(model);
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
            MediaController c = controller();
            if (c == null) {
                return false;
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
                String q = query.toString();
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
            try {
                Object app = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", mCl),
                        "currentApplication");
                return (app instanceof Context) ? (Context) app : null;
            } catch (Throwable t) {
                return null;
            }
        }

        // 状态上报（可选增强）：播放状态变化时通知宿主刷新 UI
        @SuppressWarnings("unused")
        private void notifyStatusChanged() {
            Object l = mStatusListener;
            if (l == null) {
                return;
            }
            try {
                XposedHelpers.callMethod(l, "onStatusChange");
                LogManager.d(TAG, "[" + mPkg + "] 状态上报 onStatusChange");
            } catch (Throwable ignored) {
            }
        }
    }
}
