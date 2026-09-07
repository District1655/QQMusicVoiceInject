package com.syu.voice.hook;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

/**
 * QQ音乐（车机版/HD/Pad版）第三方控制器。
 *
 * 反编译 QQ音乐HD_6.9.0.7 确认的控制接口（关键）：
 *
 * 1. 第三方控制广播接收器：
 *    com.tencent.qqmusicpad.app.reciver.BroadcastReceiverCenterForThird
 *    （exported=true，继承 com.tencent.qqmusiccar.app.reciver.BroadcastReceiverCenterForThird）
 *
 * 2. 接收器 onReceive 优先处理 intent.getData() 的 scheme（qqmusicpad:// 或 qqmusiccar://）：
 *    - action=20 → parseUrlObject → controlPlay(m0, m1)
 *        m0: 0=播放 1=暂停 2=上一首 3=下一首 4=播放MV
 *            5=收藏 6=取消收藏 7=快进 8=快退 9=停止
 *            101=单曲循环 103=列表循环 105=随机播放
 *        m1: 快进/快退为秒数，其他为100
 *    - action=8  → 点歌：search_key（Base64编码）+ m1=true（直接播放）
 *        AppStarterActivity.dispatcherThirdAction case 8:
 *        Base64.decode(search_key) → search_word → SearchFragment 搜索
 *        ★ search_key 必须 Base64 编码，明文会解码失败导致输入框为空！
 *    - action=0  → 打开 QQ音乐主页
 *
 * 3. 播放控制要求 PlayerService 已打开（QQMusicServiceProxyHelper.m()），
 *    即 QQ音乐必须先启动，否则 controlPlay 打日志 "PlayerService not opened" 直接 return。
 *
 * 注意：不能用 startActivity(scheme)！那会打开 DispacherActivityForThird(Activity)，
 * 它的 HTML scheme 分支不支持 action=20，只支持 2-8/15，控制指令会全部失效。
 * 必须用 sendBroadcast 携带 scheme data，让 BroadcastReceiverCenterForThird 处理。
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
    private static final int ACTION_SEARCH_PLAY = 8;
    private static final int ACTION_CONTROL_PLAY = 20;

    private final Context mContext;
    private final String mPkg;
    private final String mScheme;

    public QQMusicController(Context context, String pkg) {
        mContext = context;
        mPkg = pkg;
        // 根据包名选择 scheme：qqmusicpad（HD/Pad版）或 qqmusiccar（车机版）
        mScheme = pkg.contains("pad") ? "qqmusicpad" : "qqmusiccar";
    }

    /** 播放控制：play/pause/prev/next/seek/循环模式 等 */
    public void controlPlay(int ctrl, int extra) {
        String url = mScheme + "://?action=" + ACTION_CONTROL_PLAY
                + "&m0=" + ctrl + "&m1=" + extra;
        sendSchemeBroadcast(url, "controlPlay(" + ctrl + "," + extra + ")");
    }

    /** 点歌：搜索关键词并直接播放。search_key 必须是 Base64 编码，否则 QQ音乐解码失败输入框为空 */
    public void searchAndPlay(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            controlPlay(CTRL_PLAY, 100);
            return;
        }
        String b64;
        try {
            b64 = Base64.encodeToString(keyword.getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Throwable t) {
            b64 = Base64.encodeToString(keyword.getBytes(), Base64.NO_WRAP);
        }
        String url = mScheme + "://?action=" + ACTION_SEARCH_PLAY
                + "&search_key=" + b64 + "&m1=true";
        sendSchemeBroadcast(url, "searchAndPlay(" + keyword + ") b64=" + b64);
    }

    /** 打开 QQ音乐 */
    public void open() {
        String url = mScheme + "://?action=" + ACTION_OPEN;
        sendSchemeBroadcast(url, "open()");
    }

    /** 发送携带 scheme data 的广播，由 BroadcastReceiverCenterForThird 处理（不走 Activity 中转） */
    private void sendSchemeBroadcast(String url, String desc) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(mPkg);
            mContext.sendBroadcast(intent);
            LogManager.i("QQMusicController", "[" + mScheme + "] " + desc + " -> broadcast " + url);
        } catch (Throwable t) {
            LogManager.e("QQMusicController", "[" + mScheme + "] " + desc + " 发送失败", t);
        }
    }
}
