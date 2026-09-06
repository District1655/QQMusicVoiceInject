package com.syu.voice.hook;

import android.app.ActivityThread;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。
 *
 * 目标进程：com.syu.voice（方易通"车助理"语音助手，系统签名应用）
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "QQMusicVoice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.syu.voice".equals(lpparam.packageName)) {
            return;
        }
        // 初始化文件日志（Logcat + /sdcard/QQMusicVoiceInject/logs/）
        try {
            Context app = (Context) ActivityThread.currentApplication();
            if (app != null) {
                LogManager.init(app);
            }
        } catch (Throwable ignored) {
        }
        LogManager.i(TAG, "模块加载，进程: " + lpparam.processName + "，版本: " + BuildConfig.VERSION_NAME);
        MusicToolInject.hook(lpparam.classLoader);
    }
}
