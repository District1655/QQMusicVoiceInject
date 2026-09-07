package com.syu.voice.hook;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 *
 * 目标进程：
 * 1. com.syu.voice（方易通"车助理"语音助手，系统签名应用）
 *    —— 白名单注入 + MusicTool 动态代理（发广播控制）
 * 2. com.tencent.qqmusiccar / com.tencent.qqmusicpad / com.tencent.qqmusic
 *    —— 进程内 hook，直接调用 QQ音乐官方 AIDL 后台播放接口（voicePlay 等）
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "fytMusicVoice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;

        // TXZ 语音主服务（com.txznet.txz）：v1.3.2 起 hook 音乐模块 y()，
        // 确保"上一曲/下一曲/暂停"等控制命令走 MusicTool 代理而不是系统媒体键
        if ("com.txznet.txz".equals(pkg)) {
            TXZHook.hook(lpparam.classLoader);
            return;
        }

        // QQ音乐进程：v1.3.0 后台搜索直接播放（需要用户在 LSPosed 作用域勾选 QQ音乐）
        if (QQProcessHook.isQQMusicPkg(pkg)) {
            QQProcessHook.hook(pkg, lpparam.classLoader);
            return;
        }

        if (!"com.syu.voice".equals(pkg)) {
            return;
        }

        // hook Application.onCreate：在 Application 初始化完成后拿到稳定的 Context，
        // 初始化文件日志 + 设置全局 Context（供 MediaSession 控制使用）。
        // 不使用 ActivityThread.currentApplication() 反射，因为 ActivityThread 是隐藏 API，
        // 在目标进程 classLoader 里可能反射失败，导致日志和播放控制全部失效。
        XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context app = (Context) param.thisObject;
                        ContextHolder.set(app);
                        LogManager.init(app);
                        // 从模块 App 的 SharedPreferences 读取日志开关（默认开启）
                        try {
                            Context moduleCtx = app.createPackageContext(
                                    "com.syu.voice.hook", Context.CONTEXT_IGNORE_SECURITY);
                            boolean logEnabled = moduleCtx.getSharedPreferences(
                                    "fyt_music_voice_prefs", Context.MODE_PRIVATE)
                                    .getBoolean("log_enabled", true);
                            LogManager.setEnabled(logEnabled);
                        } catch (Throwable t) {
                            LogManager.w(TAG, "读取日志开关失败，使用默认开启: " + t.getMessage());
                        }
                        LogManager.i(TAG, "模块加载，进程: " + lpparam.processName
                                + "，版本: " + BuildConfig.VERSION_NAME
                                + "，Context: " + app.getPackageName()
                                + "，文件日志: " + (LogManager.isEnabled() ? "开" : "关"));
                    }
                });

        // 立即 hook 音乐工具白名单（不需要等 Application.onCreate）
        MusicToolInject.hook(lpparam.classLoader);
    }
}
