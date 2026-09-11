package com.syu.voice.hook;

/**
 * 作用域 / 日志相关常量（v1.8.8 从 MainActivity 抽出）。
 */
final class ScopeConfig {

    private ScopeConfig() {
    }

    /** 模块自身包名 */
    static final String MODULE_PKG = "com.syu.voice.hook";

    /**
     * 需要注入的作用域宿主包（不含模块自身）。与 AndroidManifest.xml 的
     * xposedscope 列表保持一致；「一键勾选作用域」「强制停止」「作用域检测」共用。
     */
    static final String[] TARGET_PACKAGES = {
            "com.syu.voice",                 // 车助理（语音助手，系统应用会自动重启）
            "com.txznet.txz",               // TXZ 语音主服务
            "com.tencent.qqmusicpad",        // QQ音乐 HD / Pad
            "com.tencent.qqmusiccar",        // QQ音乐 车机版
            "com.tencent.qqmusic",           // QQ音乐 手机版
            "com.netease.cloudmusic.iot",    // 网易云 车机版
            "com.netease.cloudmusic",        // 网易云 手机版
    };

    /**
     * 收集日志时遍历的包名 = 全部作用域宿主 + 模块自身兜底。
     *
     * v1.6.0 修正：各进程的 LogManager 写的是【宿主自己】的外部私有目录
     * （/sdcard/Android/data/<宿主pkg>/files/logs/），不是模块目录——
     * 旧列表用 com.syu.voice.hook 当"车助理进程"是错的，且漏了 TXZ（com.txznet.txz）。
     * getExternalFilesDir 失败时日志还会 fallback 到宿主内部私有目录 /data/data/<宿主pkg>/files/logs/。
     */
    static final String[] LOG_PACKAGES = {
            "com.syu.voice",                 // 车助理（语音助手）
            "com.txznet.txz",               // TXZ 语音主服务
            "com.tencent.qqmusicpad",        // QQ音乐 HD / Pad
            "com.tencent.qqmusiccar",        // QQ音乐 车机版
            "com.tencent.qqmusic",           // QQ音乐 手机版
            "com.netease.cloudmusic.iot",    // 网易云 车机版
            "com.netease.cloudmusic",        // 网易云 手机版
            "com.syu.voice.hook",            // 模块自身（旧版/兜底目录）
    };

    static final String LOG_NAME = "fytMusicVoiceInject.log";

    /** 轮转备份后缀（LogManager 1MB 轮转，保留 .1/.2/.3） */
    static final String[] LOG_SUFFIXES = {"", ".1", ".2", ".3"};

    /** LSPosed 配置数据库可能的路径（不同版本/安装方式） */
    static final String[] LSPD_DB_PATHS = {
            "/data/adb/lspd/config/modules_config.db",
            "/data/adb/modules/zygisk_lsposed/config/modules_config.db",
            "/data/adb/lspd/modules_config.db",
    };
}
