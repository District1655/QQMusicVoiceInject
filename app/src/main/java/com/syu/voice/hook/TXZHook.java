package com.syu.voice.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * TXZ 语音主服务（com.txznet.txz）进程 hook。
 *
 * v1.3.2 新增：反编译确认 TXZ 音乐模块（com.txznet.txz.module.music.b）在收到
 * "next/prev/pause/switchSong" 等控制命令时：
 *   if (y()) { MediaControlUtil.next(); }   // y()==true → 发系统媒体键（QQ音乐HD 无
 *                                           // MediaSession，收不到 → 完全没反应）
 *   else { a("next", pkg); iMusicR2.next(); } // y()==false → 走 MusicTool 代理（我们的链路）
 *
 * y() = com.txznet.txz.module.d.a.a().b()（当前是否存在"音频工具"如喜马拉雅/蜻蜓等）。
 * 只要车机上装了 TXZ 支持的音频 App 并被设为当前工具，y() 就是 true，
 * 控制指令会全部落到系统媒体键，绕过我们的 MusicTool 代理。
 *
 * 这里把 music.b 的 y() 强制改为 false，确保所有音乐控制命令都走 MusicTool 分支，
 * 由模块代理转发给 QQ音乐 AIDL 控制接口（v1.3.1 已修 PlayerService 前置检查）。
 */
public final class TXZHook {

    public static final String TAG = MainHook.TAG;

    private TXZHook() {
    }

    public static void hook(ClassLoader cl) {
        try {
            // com.txznet.txz.module.music.b（混淆后类名就是 b）私有方法 y() 返回 false
            XposedHelpers.findAndHookMethod("com.txznet.txz.module.music.b", cl,
                    "y", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(false);
                        }
                    });
            LogManager.i(TAG, "TXZ hook: 音乐模块 y() → false（控制命令强制走 MusicTool）");
        } catch (Throwable t) {
            LogManager.e(TAG, "TXZ hook music.b.y() 失败: " + t.getMessage(), t);
        }
    }
}
