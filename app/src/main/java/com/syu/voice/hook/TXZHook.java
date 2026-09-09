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
 *
 * v1.7.0 新增：NLU 本地拦截（歌单话术）。反编译 TXZ_2.9.8 确认云知声音乐技能
 * （TextYunzhishengImpl.parseOLMusic）只支持 scene=="收藏" → playFavourMusic、
 * SEARCH_RANDOM → playRandom，语料中没有"歌单/推荐歌单"概念——"播放收藏的歌单"、
 * "播放推荐歌单"等话术云端 NLU 不识别为音乐域，语音助手直接回"不知道你在说啥"。
 * hook yzsDataToTxzScene（云知声结果 → TXZ 场景 json 的转换出口），在结果
 * 未命中音乐域时按 ASR 原文本地匹配：
 *   收藏/我喜欢 + 音乐语境 → 伪造 {"scene":"music","action":"playFavourMusic"}
 *   推荐/每日推荐/随便听听 → 伪造 {"scene":"music","action":"playRandom"}
 * 之后链路与正常语音指令完全一致：music.b → MusicTool 代理 → QQ音乐 playFolderType。
 */
public final class TXZHook {

    public static final String TAG = MainHook.TAG;

    private static final String TEXT_IMPL =
            "com.txznet.txz.component.text.yunzhisheng_3_0.TextYunzhishengImpl";
    private static final String VOICE_PARSE_DATA = "com.txz.ui.voice.VoiceData$VoiceParseData";

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

        hookPlaylistNlu(cl);
    }

    /**
     * v1.7.0：hook 云知声 NLU 结果转换出口，本地补抓"歌单/收藏/推荐"话术。
     * yzsDataToTxzScene 是私有静态方法，入参/返回均为 VoiceData$VoiceParseData：
     *   strText      —— ASR 原始文本
     *   strVoiceData —— 转换后的 TXZ 场景 json（{"scene":"music","action":"play",...}）
     */
    private static void hookPlaylistNlu(ClassLoader cl) {
        try {
            Class<?> dataCls = XposedHelpers.findClass(VOICE_PARSE_DATA, cl);
            XposedHelpers.findAndHookMethod(TEXT_IMPL, cl,
                    "yzsDataToTxzScene", dataCls, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object data = param.args[0];
                                if (data == null) {
                                    return;
                                }
                                String text = (String) XposedHelpers.getObjectField(data, "strText");
                                String voice = (String) XposedHelpers.getObjectField(data, "strVoiceData");
                                if (text == null || text.isEmpty()) {
                                    return;
                                }
                                // 云端 NLU 已识别为音乐指令（点歌/收藏当前歌曲等）时不干预
                                if (voice != null && voice.contains("\"scene\":\"music\"")) {
                                    return;
                                }
                                String action = matchPlaylistAction(text);
                                if (action == null) {
                                    return;
                                }
                                String json = "{\"scene\":\"music\",\"action\":\"" + action
                                        + "\",\"text\":\"" + escapeJson(text) + "\"}";
                                XposedHelpers.setObjectField(data, "strVoiceData", json);
                                LogManager.i(TAG, "NLU 本地拦截歌单话术: \"" + text
                                        + "\" -> music/" + action);
                            } catch (Throwable t) {
                                LogManager.e(TAG, "NLU 歌单拦截处理异常: " + t.getMessage(), t);
                            }
                        }
                    });
            LogManager.i(TAG, "TXZ hook: NLU 歌单话术本地拦截已注册（收藏/我喜欢/推荐/随便听听）");
        } catch (Throwable t) {
            LogManager.e(TAG, "TXZ hook NLU 歌单拦截失败（TXZ 版本可能不同）: " + t.getMessage(), t);
        }
    }

    /**
     * 本地话术匹配。
     * 命中条件刻意收窄，避免误伤导航/电台等其他域：
     * - 收藏类：含"收藏/我喜欢/喜欢的"且含音乐语境字（歌/音乐/曲/首）；
     * - 推荐类：含"推荐"且含音乐语境字，或整句为"每日推荐/每日30首/随便听听"类常用话术。
     * （"收藏这首歌/我喜欢这首歌"等收藏当前歌曲话术云端可正常识别为音乐域，
     *   在调用本方法前已被 scene=music 判断跳过。）
     */
    static String matchPlaylistAction(String text) {
        boolean musicCtx = text.contains("歌") || text.contains("音乐")
                || text.contains("曲") || text.contains("首");
        if (text.contains("收藏") || text.contains("我喜欢") || text.contains("喜欢的")) {
            if (musicCtx) {
                return "playFavourMusic";
            }
        }
        if ((text.contains("推荐") && musicCtx)
                || text.contains("每日30首") || text.contains("每日三十首")
                || text.contains("每天30首") || text.contains("每日推荐")
                || text.contains("随便听听") || text.contains("随便来")
                || text.contains("来点好听") || text.contains("好听的")) {
            return "playRandom";
        }
        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
