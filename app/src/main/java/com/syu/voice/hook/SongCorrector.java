package com.syu.voice.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 高频歌手白名单（v1.5.0 自 v1.4.4 移植）：buildSlots 判断"纯歌手 / 歌手+歌名"用。
     *  未覆盖的歌手点歌时仍会走 QQ NLU 自由解析被猜错 → 用户反馈后往此表补充。 */
    private static final Set<String> KNOWN_SINGERS = new HashSet<String>(Arrays.asList(
            // 港台
            "周杰伦", "林俊杰", "陈奕迅", "薛之谦", "王菲", "张学友", "刘德华", "王力宏",
            "陶喆", "林宥嘉", "刘若英", "张信哲", "任贤齐", "齐秦", "张宇", "伍佰",
            "张震岳", "罗大佑", "李宗盛", "五月天", "Beyond", "陈小春", "谢霆锋", "李克勤",
            "张国荣", "谭咏麟", "黎明", "郭富城", "容祖儿", "杨千嬅", "莫文蔚", "郑秀文",
            "陈慧娴", "梁静茹", "孙燕姿", "蔡依林", "张惠妹", "田馥甄", "邓紫棋",
            // 内地
            "毛不易", "许嵩", "李荣浩", "周深", "李健", "朴树", "许巍", "汪峰", "郑钧",
            "周传雄", "张杰", "华晨宇", "那英", "韩红", "孙楠", "杨坤", "汪苏泷",
            "隔壁老樊", "海来阿木", "程响", "任然", "花粥", "凤凰传奇", "筷子兄弟"));

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

    /** 是否高频歌手白名单命中 */
    public static boolean isKnownSinger(String s) {
        return s != null && KNOWN_SINGERS.contains(s.trim());
    }

    /**
     * 从 query 构建语义槽（v1.5.0 自 v1.4.4 移植，slotList 格式见 REFERENCE.md 3.5）。
     *
     * 有槽时 QQ音乐 voicePlay 走 SearchSong 意图按槽搜索，不靠 NLU 自由解析——
     * 无槽时"毛不易"等歌手会被 NLU 猜错（实测播错歌），"周杰伦"碰巧解析对。
     *
     * 规则：
     *   1. 多词且末位/首位命中 KNOWN_SINGERS → ["Singer=歌手", "Track=其余"]
     *      （extractQuery 产出 "歌名 歌手"（歌手在末位）；兼容 TXZ 直接给 "歌手 歌名" 的情况）
     *   2. 整句即歌手 → ["Singer=歌手"]（纯歌手点歌：title=空, artist=毛不易）
     *   3. 其余 → ["Track=整句"]（纯歌名）
     *
     * @return 语义槽列表（query 为空时返回空列表）
     */
    public static List<String> buildSlots(String query) {
        List<String> slots = new ArrayList<String>();
        String s = query == null ? "" : query.trim();
        if (s.isEmpty()) {
            return slots;
        }
        String[] tokens = s.split("\\s+");
        if (tokens.length >= 2) {
            if (isKnownSinger(tokens[tokens.length - 1])) {
                slots.add("Singer=" + tokens[tokens.length - 1]);
                slots.add("Track=" + joinTokens(tokens, 0, tokens.length - 1));
                return slots;
            }
            if (isKnownSinger(tokens[0])) {
                slots.add("Singer=" + tokens[0]);
                slots.add("Track=" + joinTokens(tokens, 1, tokens.length));
                return slots;
            }
        }
        if (tokens.length == 1 && isKnownSinger(s)) {
            slots.add("Singer=" + s);
            return slots;
        }
        slots.add("Track=" + s);
        return slots;
    }

    private static String joinTokens(String[] tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tokens[i]);
        }
        return sb.toString();
    }
}
