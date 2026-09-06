package com.syu.voice.hook;

import android.content.Context;

/**
 * 全局 Application Context 持有者。
 *
 * 为什么不用 ActivityThread.currentApplication()？
 * 因为 android.app.ActivityThread 是隐藏 API，在目标进程 classLoader 里
 * 反射可能失败（XposedHelpers.findClass 抛 ClassNotFoundException），
 * 导致拿不到 Context，进而 LogManager 初始化失败、MediaSession 控制失效。
 *
 * 改为在 MainHook 里 hook Application.onCreate，在回调里设置 Context，
 * 运行时（语音指令触发播放控制）Application 早已创建，Context 一定可用。
 */
public final class ContextHolder {

    private static volatile Context sApp;

    private ContextHolder() {
    }

    public static void set(Context app) {
        if (app != null && sApp == null) {
            sApp = app.getApplicationContext();
        }
    }

    public static Context get() {
        return sApp;
    }
}
