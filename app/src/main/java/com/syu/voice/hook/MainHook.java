package com.syu.voice.hook;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 *
 * 目标进程：com.syu.voice（方易通"车助理"语音助手，系统签名应用）
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "fytMusicVoice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.syu.voice".equals(lpparam.packageName)) {
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
                        LogManager.i(TAG, "模块加载，进程: " + lpparam.processName
                                + "，版本: " + BuildConfig.VERSION_NAME
                                + "，Context: " + app.getPackageName());
                    }
                });

        // 立即 hook 音乐工具白名单（不需要等 Application.onCreate）
        MusicToolInject.hook(lpparam.classLoader);
    }
}
