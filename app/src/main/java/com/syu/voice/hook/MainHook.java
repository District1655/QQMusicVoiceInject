package com.syu.voice.hook;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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
        // v1.8.6：XposedBridge.log 直接写入 LSPosed modules.log（导出日志时可在 lsposed/ 目录拿到），
        // 不依赖 logcat 环形缓冲（chatty 会丢弃）和文件日志（Application.onCreate 之后才可用）。
        // 用于定位"模块类已加载但 hook 分支未执行/静默失败"的问题。
        try {
            XposedBridge.log("[fyt] handleLoadPackage 进入: pkg=" + pkg
                    + " process=" + lpparam.processName
                    + " cl=" + (lpparam.classLoader == null ? "null"
                    : lpparam.classLoader.getClass().getName())
                    + " v" + BuildConfig.VERSION_NAME);
        } catch (Throwable ignored) {
        }

        // v1.8.7：在 handleLoadPackage 入口立即初始化文件日志（不依赖 Context），
        // 用硬编码路径写 /sdcard/Android/data/com.syu.voice.hook/files/logs/。
        // 解决 Application.onCreate 被 SwordProxy 云控跳过导致 LogManager.init 不触发的问题。
        // 后续 attachBaseContext/onCreate 触发时会迁移到各进程自己的目录。
        if ("com.txznet.txz".equals(pkg) || QQProcessHook.isQQMusicPkg(pkg)
                || "com.syu.voice".equals(pkg)) {
            LogManager.initEarly();
        }

        try {
            // TXZ 语音主服务（com.txznet.txz）：v1.3.2 起 hook 音乐模块 y()，
            // 确保"上一曲/下一曲/暂停"等控制命令走 MusicTool 代理而不是系统媒体键
            if ("com.txznet.txz".equals(pkg)) {
                xlog("分发 -> TXZHook.hook (com.txznet.txz)");
                TXZHook.hook(lpparam.classLoader);
                xlog("TXZHook.hook 返回（注册阶段完成）");
                return;
            }

            // QQ音乐进程：v1.3.0 后台搜索直接播放（需要用户在 LSPosed 作用域勾选 QQ音乐）
            if (QQProcessHook.isQQMusicPkg(pkg)) {
                xlog("分发 -> QQProcessHook.hook (" + pkg + ")");
                QQProcessHook.hook(pkg, lpparam.classLoader);
                xlog("QQProcessHook.hook 返回（注册阶段完成）");
                return;
            }

            if (!"com.syu.voice".equals(pkg)) {
                xlog("非作用域包，跳过: " + pkg);
                return;
            }
            xlog("分发 -> com.syu.voice（车助理）hook 流程");

            // 1) 最关键：音乐工具白名单注入（不依赖 Context），必须最先注册。
            //    v1.8.9 教训：v1.8.7/1.8.8 把 Application.attachBaseContext hook 放在前面，
            //    而 attachBaseContext 定义在父类 ContextWrapper 上、Application 自身未声明，
            //    Android 10 + LSPosed 1.9.2 exact 查找抛 NoSuchMethodError，异常中断整个
            //    handleLoadPackage → MusicToolInject 未注册 → 车助理设置里选不到
            //    QQ音乐/网易云。每个 hook 独立 try/catch，互不影响。
            try {
                MusicToolInject.hook(lpparam.classLoader);
                xlog("车助理 MusicToolInject.hook 注册完成");
            } catch (Throwable t) {
                xlog("!! 车助理 MusicToolInject.hook 异常: " + t);
                XposedBridge.log(t);
            }

            // 2) Application 生命周期 hook：仅用于拿 Context 初始化文件日志。
            hookVoiceAppLifecycle(lpparam.classLoader, lpparam.processName);
            xlog("车助理 hook 注册阶段完成");
        } catch (Throwable t) {
            // 兜底：任何未预期异常只记录、不再 rethrow——rethrow 会导致本模块在该进程
            // 的后续初始化全部被 LSPosed 放弃（v1.8.7 车助理整链失效的教训）。
            XposedBridge.log("[fyt] !! handleLoadPackage 处理 " + pkg + " 时抛出异常（已吞掉，不影响其他 hook）");
            XposedBridge.log(t);
        }
    }

    /**
     * hook Application 生命周期用于初始化文件日志（车助理进程）。
     *
     * v1.8.9：attachBaseContext 必须 hook 在 android.content.ContextWrapper 上——
     * Application 自身没有声明 attachBaseContext(Context)（继承自 ContextWrapper），
     * 直接 hook "android.app.Application" 在 Android 10/LSPosed 1.9.2 上会抛
     * NoSuchMethodError#exact。ContextWrapper 的该方法还会被 Service 等子类调用，
     * 回调里用 instanceof Application 过滤。
     * 两个 hook 各自独立 try/catch，任一失败都不影响另一个与白名单注入。
     */
    private void hookVoiceAppLifecycle(final ClassLoader cl, final String processName) {
        final boolean[] appInited = {false};
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", cl,
                    "attachBaseContext", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (appInited[0] || !(param.thisObject instanceof Application)) {
                                return;
                            }
                            appInited[0] = true;
                            Context app = (Context) param.thisObject;
                            ContextHolder.set(app);
                            LogManager.init(app);
                            xlog("车助理 Application.attachBaseContext 已触发，ctx=" + app.getPackageName()
                                    + " logFile=" + LogManager.getLogFile());
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
                            LogManager.i(TAG, "模块加载，进程: " + processName
                                    + "，版本: " + BuildConfig.VERSION_NAME
                                    + "，Context: " + app.getPackageName()
                                    + "，文件日志: " + (LogManager.isEnabled() ? "开" : "关"));
                        }
                    });
            xlog("车助理 hook ContextWrapper.attachBaseContext 注册成功");
        } catch (Throwable t) {
            xlog("车助理 hook ContextWrapper.attachBaseContext 失败（不影响功能）: " + t);
        }
        // onCreate 作为双保险
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (appInited[0]) return;
                            appInited[0] = true;
                            Context app = (Context) param.thisObject;
                            ContextHolder.set(app);
                            LogManager.init(app);
                            xlog("车助理 Application.onCreate 已触发，ctx=" + app.getPackageName()
                                    + " logFile=" + LogManager.getLogFile());
                            LogManager.i(TAG, "模块加载，进程: " + processName
                                    + "，版本: " + BuildConfig.VERSION_NAME
                                    + "，Context: " + app.getPackageName()
                                    + "，文件日志: " + (LogManager.isEnabled() ? "开" : "关"));
                        }
                    });
            xlog("车助理 hook Application.onCreate 注册成功");
        } catch (Throwable t) {
            xlog("车助理 hook Application.onCreate 失败（不影响功能）: " + t);
        }
    }

    /** XposedBridge.log 包装（统一前缀），写入 LSPosed modules.log，随日志包导出。 */
    static void xlog(String msg) {
        try {
            XposedBridge.log("[fyt] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
