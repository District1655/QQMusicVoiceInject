package com.syu.voice.hook;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * QQ音乐（车机版/HD/Pad版）第三方控制器。
 *
 * 反编译 QQ音乐HD_6.9.0.7 确认：QQ音乐通过 scheme 方式暴露第三方控制接口，
 * 不依赖 Android MediaSession（QQ音乐HD 不暴露活跃 MediaSession，导致
 * MediaSessionManager.getActiveSessions 返回空，播放控制全部失效）。
 *
 * Scheme 格式：
 *   qqmusicpad://?action={action}&m0={ctrl}&m1={extra}    （HD/Pad版）
 *   qqmusiccar://?action={action}&m0={ctrl}&m1={extra}    （车机版）
 *
 * action 类型：
 *   0  = 打开 QQ音乐
 *   1  = 关闭 QQ音乐
 *   8  = 搜索播放（需 search_key 参数，m1=true 表示直接播放）
 *   20 = 播放控制（需 m0/m1 参数）
 *   100 = 允许外部发送广播
 *
 * controlPlay m0 参数（播放控制类型）：
 *   0=播放, 1=暂停, 2=上一首, 3=下一首, 4=播放MV,
 *   5=收藏, 6=取消收藏, 7=快进(秒), 8=快退(秒), 9=停止,
 *   101=单曲循环, 103=列表循环, 105=随机播放
 */
public final class QQMusicController {

    public static final int CTRL_PLAY = 0;
    public static final int CTRL_PAUSE = 1;
    public static final int CTRL_PREV = 2;
    public static final int CTRL_NEXT = 3;
    public static final int CTRL_PLAY_MV = 4;
    public static final int CTRL_FAV = 5;
    public static final int CTRL_UNFAV = 6;
    public static final int CTRL_SEEK_FORWARD = 7;
    public static final int CTRL_SEEK_BACKWARD = 8;
    public static final int CTRL_STOP = 9;
    public static final int CTRL_MODE_SINGLE = 101;
    public static final int CTRL_MODE_LIST = 103;
    public static final int CTRL_MODE_SHUFFLE = 105;

    private static final int ACTION_OPEN = 0;
    private static final int ACTION_CONTROL_PLAY = 20;
    private static final int ACTION_SEARCH_PLAY = 8;

    private final Context mContext;
    private final String mScheme;

    public QQMusicController(Context context, String pkg) {
        mContext = context;
        // 根据包名选择 scheme：qqmusicpad（HD/Pad版）或 qqmusiccar（车机版）
        mScheme = pkg.contains("pad") ? "qqmusicpad" : "qqmusiccar";
    }

    /** 播放控制：play/pause/prev/next/seek 等 */
    public void controlPlay(int ctrl, int extra) {
        String url = mScheme + "://?action=" + ACTION_CONTROL_PLAY
                + "&m0=" + ctrl + "&m1=" + extra;
        startScheme(url, "controlPlay(" + ctrl + "," + extra + ")");
    }

    /** 点歌：搜索关键词并直接播放 */
    public void searchAndPlay(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            controlPlay(CTRL_PLAY, 100);
            return;
        }
        String url = mScheme + "://?action=" + ACTION_SEARCH_PLAY
                + "&search_key=" + Uri.encode(keyword, "UTF-8")
                + "&m1=true";
        startScheme(url, "searchAndPlay(" + keyword + ")");
    }

    /** 打开 QQ音乐 */
    public void open() {
        String url = mScheme + "://?action=" + ACTION_OPEN;
        startScheme(url, "open()");
    }

    private void startScheme(String url, String desc) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mContext.startActivity(intent);
            LogManager.i("QQMusicController", "[" + mScheme + "] " + desc + " -> " + url);
        } catch (Throwable t) {
            LogManager.e("QQMusicController", "[" + mScheme + "] " + desc + " 失败", t);
        }
    }
}
