package com.syu.voice.hook;

import java.util.HashMap;
import java.util.Map;

/**
 * 语音点歌"识别纠错"层。
 *
 * 车机语音（TXZ/车助理）的 ASR + NLU 对歌手/歌名等专有名词经常产生同音/近音错字，
 * 例如"毛不易"识别成"毛不义"、"像我这样的人"识别成"像我这样得人"，
 * 错词直接进 QQ音乐搜索必然搜出错误内容。
 *
 * 本类在点歌 query 到达播放器之前做映射纠错：
 *   1. 整句替换：覆盖歌名整串误识别（含在 query 子串中）；
 *   2. 词级替换：按空白拆分，逐 token 精确匹配歌手/词条误识别；
 *   3. 命中即记录日志（识别词 -> 纠错词），便于后续按实际日志扩充字典。
 *
 * 字典为内置静态数据，随版本迭代补充；新增条目只需在下方 MAP 中添加。
 */
public final class SongCorrector {

    private static final String TAG = MainHook.TAG;

    /** 整句/子串级纠错：key=误识别文本（可长、可含子串），value=正确文本 */
    private static final Map<String, String> PHRASE_CORRECTIONS = new HashMap<String, String>();

    /** 词级纠错：key=单个词（按空白拆分后精确匹配），value=正确词 */
    private static final Map<String, String> TOKEN_CORRECTIONS = new HashMap<String, String>();

    static {
        // ------------------------------------------------------------------
        // 歌手：毛不易（Máo Bù Yì）常见同音/近音误识别
        // ------------------------------------------------------------------
        TOKEN_CORRECTIONS.put("毛不义", "毛不易");
        TOKEN_CORRECTIONS.put("毛布衣", "毛不易");
        TOKEN_CORRECTIONS.put("毛布艺", "毛不易");
        TOKEN_CORRECTIONS.put("毛不逸", "毛不易");
        TOKEN_CORRECTIONS.put("毛不一", "毛不易");
        TOKEN_CORRECTIONS.put("毛布易", "毛不易");
        TOKEN_CORRECTIONS.put("毛艺", "毛不易");
        TOKEN_CORRECTIONS.put("毛益", "毛不易");
        TOKEN_CORRECTIONS.put("茅不易", "毛不易");

        // ------------------------------------------------------------------
        // 歌曲：《像我这样的人》常见同音/近音误识别
        // ------------------------------------------------------------------
        PHRASE_CORRECTIONS.put("像我这样得人", "像我这样的人");
        PHRASE_CORRECTIONS.put("像我这样滴人", "像我这样的人");
        PHRASE_CORRECTIONS.put("像我这样德人", "像我这样的人");
        PHRASE_CORRECTIONS.put("像我这样的认", "像我这样的人");
        PHRASE_CORRECTIONS.put("象我这样的人", "像我这样的人");
        PHRASE_CORRECTIONS.put("象我这样得人", "像我这样的人");
        PHRASE_CORRECTIONS.put("像我着样的人", "像我这样的人");

        // ------------------------------------------------------------------
        // 高频歌手常见同音误识别（高置信度，随日志持续补充）
        // ------------------------------------------------------------------
        TOKEN_CORRECTIONS.put("周杰轮", "周杰伦");
        TOKEN_CORRECTIONS.put("周捷伦", "周杰伦");
        TOKEN_CORRECTIONS.put("薛之前", "薛之谦");
        TOKEN_CORRECTIONS.put("薛之千", "薛之谦");
        TOKEN_CORRECTIONS.put("王菲儿", "王菲");
        TOKEN_CORRECTIONS.put("邓紫旗", "邓紫棋");
        TOKEN_CORRECTIONS.put("邓子琪", "邓紫棋");
        TOKEN_CORRECTIONS.put("林俊洁", "林俊杰");
        TOKEN_CORRECTIONS.put("林俊捷", "林俊杰");
        TOKEN_CORRECTIONS.put("陈亦迅", "陈奕迅");
        TOKEN_CORRECTIONS.put("陈奕训", "陈奕迅");
        TOKEN_CORRECTIONS.put("许嵩山", "许嵩");
        TOKEN_CORRECTIONS.put("李荣浩浩", "李荣浩");
        TOKEN_CORRECTIONS.put("李荣好", "李荣浩");
        TOKEN_CORRECTIONS.put("刘若鹰", "刘若英");
        TOKEN_CORRECTIONS.put("刘若因", "刘若英");
        TOKEN_CORRECTIONS.put("张学有", "张学友");
        TOKEN_CORRECTIONS.put("张学右", "张学友");
    }

    private SongCorrector() {
    }

    /**
     * 对点歌 query（形如"歌名 歌手"）做识别纠错。
     * 先做整句/子串级替换，再做词级替换；命中任意一级都记录日志。
     *
     * @return 纠错后的 query（无命中时原样返回）
     */
    public static String correctQuery(String query) {
        if (query == null) {
            return "";
        }
        String s = query.trim();
        if (s.isEmpty()) {
            return s;
        }
        String original = s;
        String result = s;

        // 1) 整句/子串级替换（歌名等长文本误识别）
        for (Map.Entry<String, String> e : PHRASE_CORRECTIONS.entrySet()) {
            if (result.contains(e.getKey())) {
                result = result.replace(e.getKey(), e.getValue());
            }
        }

        // 2) 词级替换（歌手名等单词语误识别）
        String[] tokens = result.split("\\s+");
        boolean tokenChanged = false;
        for (int i = 0; i < tokens.length; i++) {
            String fixed = TOKEN_CORRECTIONS.get(tokens[i]);
            if (fixed != null) {
                tokens[i] = fixed;
                tokenChanged = true;
            }
        }
        if (tokenChanged) {
            StringBuilder sb = new StringBuilder();
            for (String t : tokens) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
            result = sb.toString();
        }

        if (!result.equals(original)) {
            LogManager.i(TAG, "识别纠错命中: \"" + original + "\" -> \"" + result
                    + "\"（如需补充更多误识别词，请把此条日志发给我）");
        }
        return result;
    }
}
