package com.syu.voice.hook;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 核心注入逻辑。
 *
 * 反编译结论（车助理设置_1.0.apk, com.syu.voice）：
 * 1. VoiceAdapter$NaviTools.mSupportMusicTools 是"支持的音乐工具"白名单，
 *    checkMusicTools(Context) 会把已安装且命中白名单的包名填入
 *    mlocalMusicTools，后者是"车助理设置->音乐工具选择"列表的数据源。
 * 2. supportMusicTool(String) 硬编码判断包名是否受支持。
 * 3. setMusicTool(String) 按包名把对应的 MusicTool 实现注册给
 *    TXZMusicManager（com.txznet.sdk），语音指令经宿主(TXZ 2.9.8)转发回该实现。
 * 4. 现有白名单：cn.kuwo.player / cn.kuwo.kwmusiccar / com.txznet.music /
 *    com.syu.music / com.tencent.wecarflow / com.kugou.android.auto —— 无目标音乐系列。
 *
 * 注入策略：把目标音乐包名补进白名单，并让 setMusicTool 为其注册
 * 基于 MediaSession 控制的代理 MusicTool（QQMusicToolProxy）。
 * 所有关键路径输出 Logcat + 文件日志（LogManager），便于车机离线排障。
 */
public final class MusicToolInject {

    public static final String TAG = MainHook.TAG;

    /** 需要支持的播放器包名（可自行增删；需该播放器实现 MediaSession） */
    public static final String[] TARGET_MUSIC_PKGS = {
            "com.tencent.qqmusiccar",        // QQ音乐车机版（导航/车机市场版本）
            "com.tencent.qqmusic",           // QQ音乐手机版
            "com.tencent.qqmusicpad",        // QQ音乐 HD / Pad 版
            "com.netease.cloudmusic.iot",    // 网易云音乐车机版（IoT 开放平台版）
            "com.netease.cloudmusic",        // 网易云音乐手机版
    };

    private static final String NAVI_TOOLS = "com.syu.voice.VoiceAdapter$NaviTools";
    private static final String MUSIC_MANAGER = "com.txznet.sdk.TXZMusicManager";

    private static volatile boolean sHooked = false;

    private MusicToolInject() {
    }

    public static void hook(final ClassLoader cl) {
        if (sHooked) {
            return;
        }
        sHooked = true;

        try {
            // ------------------------------------------------------------
            // 1) checkMusicTools：保证白名单已注入，设置界面能列出目标播放器
            // ------------------------------------------------------------
            XposedHelpers.findAndHookMethod(NAVI_TOOLS, cl, "checkMusicTools",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ensureSupportList(cl);
                        }
                    });

            // ------------------------------------------------------------
            // 2) supportMusicTool：目标包名一律视为支持
            // ------------------------------------------------------------
            XposedHelpers.findAndHookMethod(NAVI_TOOLS, cl, "supportMusicTool",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ensureSupportList(cl);
                            String pkg = (String) param.args[0];
                            if (isTargetMusic(pkg)) {
                                LogManager.i(TAG, "supportMusicTool 放行: " + pkg);
                                param.setResult(true);
                            }
                        }
                    });

            // ------------------------------------------------------------
            // 3) getInstalledMusic：默认音乐工具兜底，优先目标播放器（可选增强）
            // ------------------------------------------------------------
            XposedHelpers.findAndHookMethod(NAVI_TOOLS, cl, "getInstalledMusic",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ensureSupportList(cl);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof String) || !isTargetMusic((String) result)) {
                                String installed = findInstalledTargetMusic((Context) param.args[0]);
                                if (installed != null) {
                                    LogManager.i(TAG, "getInstalledMusic 兜底 -> " + installed);
                                    param.setResult(installed);
                                }
                            }
                        }
                    });

            // ------------------------------------------------------------
            // 4) setMusicTool：目标包名 -> 注册 MediaSession 代理 MusicTool
            // ------------------------------------------------------------
            XposedHelpers.findAndHookMethod(NAVI_TOOLS, cl, "setMusicTool",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ensureSupportList(cl);
                            String pkg = (String) param.args[0];
                            if (!isTargetMusic(pkg)) {
                                return;
                            }
                            try {
                                Object manager = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass(MUSIC_MANAGER, cl), "getInstance");
                                Object proxy = QQMusicToolProxy.create(cl, pkg);
                                XposedHelpers.callMethod(manager, "setMusicTool", proxy);
                                // v1.8.12：缓存代理实例，ScenePlaylistHook 直接据此派发，
                                // 不再依赖混淆字段（SDK 2.9.8 中字段为 Object i，按类型找不到）
                                ScenePlaylistHook.registerTool(pkg, proxy);
                                LogManager.i(TAG, "已为 " + pkg + " 注册 MediaSession 音乐工具");
                            } catch (Throwable t) {
                                LogManager.e(TAG, "注册音乐工具失败: " + pkg, t);
                            }
                            // 拦截原实现（原实现对该包名无分支，直接返回）
                            param.setResult(null);
                        }
                    });

            // ------------------------------------------------------------
            // 5) v1.8.11：车助理场景链（VoiceAdapter$17/$18）歌单兜底拦截。
            //    云端把"播放收藏的歌吧"下发成 model.keywords（title 为空）时，
            //    在车助理进程内按 ASR 原文直接路由到本模块 MusicTool。
            //    独立 try/catch，注册失败不影响白名单注入。
            // ------------------------------------------------------------
            try {
                ScenePlaylistHook.hook(cl);
            } catch (Throwable t) {
                LogManager.e(TAG, "ScenePlaylistHook 注册异常", t);
            }

            LogManager.i(TAG, "hook 完成，目标包名: " + java.util.Arrays.toString(TARGET_MUSIC_PKGS));
        } catch (Throwable t) {
            LogManager.e(TAG, "hook 失败", t);
            XposedBridge.log(TAG + " hook 失败: " + Log.getStackTraceString(t));
        }
    }

    /** 把目标包名补充进 mSupportMusicTools（幂等） */
    private static void ensureSupportList(ClassLoader cl) {
        try {
            Object list = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(NAVI_TOOLS, cl), "mSupportMusicTools");
            if (list instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<String> support = (java.util.List<String>) list;
                boolean changed = false;
                for (String pkg : TARGET_MUSIC_PKGS) {
                    if (!support.contains(pkg)) {
                        support.add(pkg);
                        changed = true;
                        LogManager.i(TAG, "白名单注入: " + pkg);
                    }
                }
                if (!changed) {
                    LogManager.d(TAG, "白名单已包含全部目标包名，跳过注入");
                }
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "注入 mSupportMusicTools 失败", t);
        }
    }

    static boolean isTargetMusic(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : TARGET_MUSIC_PKGS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static String findInstalledTargetMusic(Context context) {
        try {
            for (String pkg : TARGET_MUSIC_PKGS) {
                context.getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
