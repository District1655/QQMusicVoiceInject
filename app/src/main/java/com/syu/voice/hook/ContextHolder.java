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
 * 在 MainHook 里 hook Application.attachBaseContext / onCreate，在回调里
 * 设置 Context，运行时（语音指令触发播放控制）Application 早已创建，Context 可用。
 *
 * v1.8.10 血泪教训：attachBaseContext 阶段绝不能直接存 getApplicationContext()！
 * 框架调用顺序是 LoadedApk.makeApplication() →
 * Instrumentation.newApplication()（内部 app.attach(base) → attachBaseContext）
 * → 返回后才执行 mApplication = app。所以 attachBaseContext 回调期间
 * ContextImpl.getApplicationContext() 经 mPackageInfo.getApplication() 取到的
 * LoadedApk.mApplication 还是 null。v1.8.7 起在 attachBaseContext 回调里
 * set(getApplicationContext()) 会把 sApp 写成 null（且本方法只写一次），
 * v1.8.9 修好 attach hook 注册后该回调首次真正执行，onCreate 回调又被防重
 * 标志跳过 → sApp 永久为 null → QQMusicToolProxy.qq() 返回 null → 点歌全部
 * 降级到 QQ音乐HD 不支持的 MediaSession playFromSearch → 点歌/控制全失效。
 * 修复：applicationContext 为 null 时回退保存 Application 对象本身——
 * attachBaseContext after 阶段其 mBase 已绑定，作为 Context 完全可用，
 * 且它就是后续 onCreate 阶段 getApplicationContext() 返回的同一对象。
 */
public final class ContextHolder {

    private static volatile Context sApp;

    private ContextHolder() {
    }

    public static void set(Context app) {
        if (app == null || sApp != null) {
            return;
        }
        Context ac = app.getApplicationContext();
        sApp = ac != null ? ac : app;
    }

    public static Context get() {
        return sApp;
    }
}
