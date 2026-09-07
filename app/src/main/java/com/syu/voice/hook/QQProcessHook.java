package com.syu.voice.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * QQ音乐进程内注入（v1.3.0 新增）。
 *
 * 目标进程：com.tencent.qqmusiccar（车机版）/ com.tencent.qqmusicpad（HD/Pad版）/
 * com.tencent.qqmusic（手机版）。用户需在 LSPosed 作用域中额外勾选已安装的 QQ音乐包。
 *
 * 做三件事：
 * 1. 捕获 ApiMethodsImpl 实例（hook ApiMethodsImpl 私有构造 + QQMusicApiService.onCreate），
 *    供 ApiHolder 进程内直接调用官方 AIDL 后台播放接口；
 * 2. hook BroadcastReceiverCenterForThird.onReceive（父类，pad 子类继承）：
 *    - action=8 点歌 -> 拦截 -> ApiHolder.voicePlay(query) 后台搜索直接播放（不弹搜索框 UI）
 *    - action=20 播放控制 -> 拦截 -> ApiHolder.control(m0, m1) 直接调播放器
 *    hook 未生效/实例未就绪时放行原逻辑（不退化）；
 * 3. Application.onCreate 时主动 bind QQMusicApiService，确保 ApiMethodsImpl 实例存在。
 */
public final class QQProcessHook {

    public static final String TAG = MainHook.TAG;

    private static final String RECEIVER_CAR =
            "com.tencent.qqmusiccar.app.reciver.BroadcastReceiverCenterForThird";
    private static final String API_SERVICE_CAR =
            "com.tencent.qqmusiccar.third.api.QQMusicApiService";
    private static final String API_IMPL =
            "com.tencent.qqmusiccar.third.api.apiImpl.ApiMethodsImpl";

    private QQProcessHook() {
    }

    /** 是否 QQ音乐目标包 */
    public static boolean isQQMusicPkg(String pkg) {
        return "com.tencent.qqmusiccar".equals(pkg)
                || "com.tencent.qqmusicpad".equals(pkg)
                || "com.tencent.qqmusic".equals(pkg);
    }

    public static void hook(final String pkg, final ClassLoader cl) {
        ApiHolder.init(cl);

        // 1) hook ApiMethodsImpl 私有构造：任何路径创建都捕获实例
        try {
            XposedHelpers.findAndHookConstructor(API_IMPL, cl, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ApiHolder.set(param.thisObject);
                }
            });
            LogManager.d(TAG, "[" + pkg + "] hook ApiMethodsImpl 构造成功");
        } catch (Throwable t) {
            LogManager.d(TAG, "[" + pkg + "] hook ApiMethodsImpl 构造跳过: " + t.getMessage());
        }

        // 2) hook QQMusicApiService.onCreate：拿 service -> e(QQMusicApiImpl) -> e(ApiMethodsImpl)
        try {
            XposedHelpers.findAndHookMethod(API_SERVICE_CAR, cl, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            captureFromService(param.thisObject);
                        }
                    });
            LogManager.d(TAG, "[" + pkg + "] hook QQMusicApiService.onCreate 成功");
        } catch (Throwable t) {
            LogManager.d(TAG, "[" + pkg + "] hook QQMusicApiService.onCreate 跳过: " + t.getMessage());
        }

        // 3) hook 第三方控制广播接收器：拦截点歌/播放控制，改走内部 API 后台播放
        try {
            XposedHelpers.findAndHookMethod(RECEIVER_CAR, cl,
                    "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.args[0];
                                Intent intent = (Intent) param.args[1];
                                if (interceptBroadcast(intent)) {
                                    param.setResult(null); // 消费广播，阻止弹搜索框/原逻辑
                                }
                            } catch (Throwable t) {
                                LogManager.e(TAG, "拦截广播异常，放行原逻辑", t);
                            }
                        }
                    });
            LogManager.i(TAG, "[" + pkg + "] 已 hook 第三方控制广播（点歌/播放控制将走内部 API 后台播放）");
        } catch (Throwable t) {
            LogManager.e(TAG, "[" + pkg + "] hook BroadcastReceiverCenterForThird 失败", t);
        }

        // 4) Application.onCreate：初始化日志 + 主动 bind ApiService 确保实例存在
        XposedHelpers.findAndHookMethod("android.app.Application", cl,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Context app = (Context) param.thisObject;
                        ContextHolder.set(app);
                        LogManager.init(app);
                        try {
                            Context moduleCtx = app.createPackageContext(
                                    "com.syu.voice.hook", Context.CONTEXT_IGNORE_SECURITY);
                            boolean logEnabled = moduleCtx.getSharedPreferences(
                                    "fyt_music_voice_prefs", Context.MODE_PRIVATE)
                                    .getBoolean("log_enabled", true);
                            LogManager.setEnabled(logEnabled);
                        } catch (Throwable t) {
                            LogManager.w(TAG, "[" + pkg + "] 读取日志开关失败，默认开启");
                        }
                        LogManager.i(TAG, "[" + pkg + "] 模块加载（QQ音乐进程）v"
                                + BuildConfig.VERSION_NAME);
                        ensureApiService(app, pkg);
                    }
                });
    }

    /** 从 QQMusicApiService 实例反射取 e.e 得到 ApiMethodsImpl */
    private static void captureFromService(Object service) {
        try {
            Object apiImpl = XposedHelpers.getObjectField(service, "e"); // QQMusicApiImpl
            Object methodsImpl = XposedHelpers.getObjectField(apiImpl, "e"); // ApiMethodsImpl
            ApiHolder.set(methodsImpl);
        } catch (Throwable t) {
            LogManager.w(TAG, "从 QQMusicApiService 取 ApiMethodsImpl 失败: " + t.getMessage());
        }
    }

    /** 主动 bind 第三方 API 服务，确保 ApiMethodsImpl 被创建 */
    private static void ensureApiService(final Context app, final String pkg) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String serviceClass = pkg + ".third.api.QQMusicApiService";
                    Intent intent = new Intent();
                    intent.setClassName(pkg, serviceClass);
                    boolean ok = app.bindService(intent, new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            LogManager.i(TAG, "[" + pkg + "] ApiService 已连接: " + name);
                            if (!ApiHolder.isReady()) {
                                // 有些版本 onCreate 先于 onBind 执行，构造 hook 已捕获；
                                // 兜底：从 service 静态实例反射（不适用），仅记录
                                LogManager.w(TAG, "[" + pkg + "] 连接成功但 ApiMethodsImpl 未就绪");
                            }
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                        }
                    }, Context.BIND_AUTO_CREATE);
                    LogManager.i(TAG, "[" + pkg + "] 主动 bind ApiService(" + serviceClass + ") -> " + ok);
                } catch (Throwable t) {
                    LogManager.e(TAG, "[" + pkg + "] bind ApiService 失败", t);
                }
            }
        }, 2000);
    }

    /** 拦截 qqmusicpad:// / qqmusiccar:// 广播中的 action=8（点歌）与 action=20（控制） */
    private static boolean interceptBroadcast(Intent intent) {
        if (intent == null) {
            return false;
        }
        Uri uri = intent.getData();
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"qqmusicpad".equals(scheme) && !"qqmusiccar".equals(scheme)) {
            return false;
        }
        String actionStr = uri.getQueryParameter("action");
        if (actionStr == null || actionStr.isEmpty()) {
            return false;
        }
        int action;
        try {
            action = Integer.parseInt(actionStr);
        } catch (Throwable t) {
            return false;
        }
        try {
            if (action == 8) {
                // 点歌：search_key -> 后台 voicePlay
                String key = uri.getQueryParameter("search_key");
                String query = ApiHolder.decodeSearchKey(key);
                LogManager.i(TAG, "[拦截] 点歌 action=8 search_key=" + key + " -> query=" + query);
                if (query != null && !query.trim().isEmpty()) {
                    if (ApiHolder.voicePlay(query)) {
                        LogManager.i(TAG, ">>> 已走内部 API 后台播放（不弹搜索框）");
                        return true;
                    }
                    LogManager.w(TAG, "voicePlay 未就绪，放行原逻辑");
                }
                return false;
            }
            if (action == 20) {
                // 播放控制：m0=0/1/2/3/7/8/9/101/103/105
                String m0 = uri.getQueryParameter("m0");
                String m1 = uri.getQueryParameter("m1");
                int cmd = -1;
                long extra = 0;
                try {
                    cmd = Integer.parseInt(m0);
                    extra = Long.parseLong(m1);
                } catch (Throwable ignored) {
                }
                LogManager.i(TAG, "[拦截] 播放控制 action=20 m0=" + m0 + " m1=" + m1);
                if (ApiHolder.control(cmd, extra)) {
                    LogManager.i(TAG, ">>> 已走内部 API 播放控制");
                    return true;
                }
                LogManager.w(TAG, "control 未就绪，放行原逻辑");
                return false;
            }
        } catch (Throwable t) {
            LogManager.e(TAG, "拦截 action=" + action + " 异常", t);
        }
        return false;
    }
}
