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
    /**
     * v1.7.0 新增：歌单播放（模块自定义 action，QQ音乐原生 receiver 不识别，
     * 必须由 QQProcessHook 拦截后走 ApiMethodsImpl 内部 API）：
     *   m0=201 收藏歌曲；m0=104 猜你喜欢（个人电台）；
     *   m0=108 每日30首（取列表 mid → playSongMid）；m0=2 排行榜（取榜单→取歌曲→播放）
     */
    private static final int ACTION_FOLDER_PLAY = 30;

    /** 歌单类型，与 ApiHolder 常量对应：201=收藏，104=猜你喜欢，108=每日30首，2=排行榜 */
    public static final int FOLDER_FAVOURITE = 201;
    public static final int FOLDER_PERSONAL_RADIO = 104;
    public static final int FOLDER_DAILY_30 = 108;
    public static final int FOLDER_RANK = 2;

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
        boolean started = ensureRunning();
        sendSchemeBroadcast(url, "controlPlay(" + ctrl + "," + extra + ")");
        if (started) {
            // 冷启动：广播可能早于播放器进程内 hook 就绪被原生链路抢走/丢失，延迟重发覆盖就绪窗口
            scheduleRetry(url, "controlPlay(" + ctrl + "," + extra + ") 冷启动重发");
        }
    }

    /**
     * 点歌：搜索关键词并直接播放。search_key 必须是 Base64 编码，否则 QQ音乐解码失败输入框为空。
     * ★ Base64 拼进 URL query 前必须 URLEncoder：标准 Base64 含 '+' '/' '='，
     *   query string 按 form-urlencoded 解析时 '+' 会被解码成空格（Android Uri.getQueryParameter
     *   与 URLDecoder 均如此），导致 Base64 解码出乱码、搜索播错歌。
     *   实测："毛不易" 的 b64=5q+b5LiN5piT（含 '+'）被解析成 "5q b5LiN5piT" → 乱码；
     *   "周杰伦" 的 b64=5ZGo5p2w5Lym（不含 +/=）恰好正常——与歌手名气无关。
     */
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
        // URL 编码：'+'→%2B、'/'→%2F、'='→%3D；接收端（模块 getQueryParameter 与
        // QQ音乐原生 receiver 的 URL 解码）都会还原为标准 Base64 字母
        String encB64 = b64;
        try {
            encB64 = java.net.URLEncoder.encode(b64, "UTF-8");
        } catch (Throwable ignored) {
        }
        String url = mScheme + "://?action=" + ACTION_SEARCH_PLAY
                + "&search_key=" + encB64 + "&m1=true";
        String desc = "searchAndPlay(" + keyword + ") b64=" + b64 + " enc=" + encB64;
        boolean started = ensureRunning();
        sendSchemeBroadcast(url, desc);
        if (started) {
            // 冷启动：播放器进程刚拉起，广播可能早于进程内 hook 就绪被原生链路抢走/丢失
            // （实测冷启动后 hook 就绪可能需要数十秒），延迟重发覆盖就绪窗口；
            // 拦截端对相同 query 短窗口去重，已播放过的不会重复点歌
            scheduleRetry(url, desc + " 冷启动重发");
        }
    }

    /**
     * 歌单/电台播放（v1.7.0）：action=30 由播放器进程内模块拦截，走官方
     * ApiMethodsImpl.playFolderType 后台播放（不跳页面）。
     * 原生 receiver 不识别 action=30，故冷启动时依赖 ensureRunning 拉起 QQ音乐 +
     * 4/9/15/25s 重发覆盖 hook 就绪窗口（与点歌/控制同一机制）。
     */
    public void playFolder(int folderType) {
        String url = mScheme + "://?action=" + ACTION_FOLDER_PLAY + "&m0=" + folderType;
        String label;
        switch (folderType) {
            case FOLDER_FAVOURITE: label = "=收藏歌曲"; break;
            case FOLDER_PERSONAL_RADIO: label = "=猜你喜欢"; break;
            case FOLDER_DAILY_30: label = "=每日30首"; break;
            case FOLDER_RANK: label = "=排行榜"; break;
            default: label = ""; break;
        }
        String desc = "playFolder(" + folderType + label + ")";
        boolean started = ensureRunning();
        sendSchemeBroadcast(url, desc);
        if (started) {
            scheduleRetry(url, desc + " 冷启动重发");
        }
    }

    /** 打开 QQ音乐 */
    public void open() {
        String url = mScheme + "://?action=" + ACTION_OPEN;
        sendSchemeBroadcast(url, "open()");
    }

    /**
     * 冷启动重发：播放器进程刚被拉起时，广播可能早于进程内模块 hook 就绪
     * （被原生 receiver 抢走处理或进程未完全启动而丢失），实测 hook 就绪可能需数十秒。
     * 在 4s/9s/15s/25s 重发同一广播：hook 就绪后第一次重发即被拦截走 voicePlay，
     * 拦截端对相同指令短窗口去重（已成功的不再重复执行）；hook 始终不就绪时
     * 原生 receiver 也会按 action=8 搜索播放（与旧版行为一致）。
     */
    private void scheduleRetry(final String url, final String desc) {
        final long[] delays = {4000, 9000, 15000, 25000};
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (long d : delays) {
                    try {
                        Thread.sleep(d);
                    } catch (InterruptedException e) {
                        return;
                    }
                    // 播放器已退出（用户手动杀掉）则不再重发
                    if (!isPkgRunning(mPkg)) {
                        LogManager.i("QQMusicController", "[" + mScheme + "] 冷启动重发取消："
                                + mPkg + " 已不在运行");
                        return;
                    }
                    sendSchemeBroadcast(url, desc + " (+" + d + "ms)");
                }
            }
        }, "fyt-coldstart-retry").start();
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

    /**
     * 确保 QQ音乐在运行：检测不到其进程时，先启动它再操作。
     * 车助理是系统 uid，getRunningAppProcesses 可枚举全部进程；拿不到列表时
     * 默认视为已运行，避免误启动打断正在播放的音乐。
     *
     * @return true=本次调用触发了启动（冷启动场景，调用方应延迟重发广播）
     */
    private boolean ensureRunning() {
        try {
            if (isPkgRunning(mPkg)) {
                return false;
            }
            LogManager.i("QQMusicController", "[" + mPkg + "] 未在运行，先启动…");
            Intent launch = mContext.getPackageManager().getLaunchIntentForPackage(mPkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(launch);
                LogManager.i("QQMusicController", "[" + mPkg + "] 已发送启动 Intent");
                return true;
            } else {
                LogManager.w("QQMusicController", "[" + mPkg + "] 无启动 Intent（未安装？）");
            }
        } catch (Throwable t) {
            LogManager.e("QQMusicController", "[" + mPkg + "] 启动失败，继续发广播", t);
        }
        return false;
    }

    /** 检测目标包是否有存活进程（QQ音乐多进程，任一进程存活即视为运行中） */
    private boolean isPkgRunning(String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return true;
            }
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs =
                    am.getRunningAppProcesses();
            if (procs == null || procs.isEmpty()) {
                return true; // 拿不到列表，不误启动
            }
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                if (pkg.equals(p.processName)) {
                    return true;
                }
                if (p.pkgList != null) {
                    for (String pp : p.pkgList) {
                        if (pkg.equals(pp)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogManager.e("QQMusicController", "检测进程失败，默认已运行", t);
            return true;
        }
        return false;
    }
}
