package com.dsh.biime;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扩展包：内置包 + 用户导入的包。
 *
 * 一个包能做四类事（可任意组合）：
 *   theme 主题     —— 键盘 / 首页配色
 *   keys  键盘样式 —— 键帽圆角、键高、字号、数字行
 *   dict  词典     —— 往候选里加词条（拼音 + 中文 + 英文）
 *   func  功能     —— 默认输出英文、默认繁体、空格上屏首选词
 *
 * 导入的包以 JSON 存在应用私有目录 files/packs/id.json，不需要任何存储权限。
 */
public final class Packs {

    public static final String CAT_ALL = "all";
    public static final String CAT_THEME = "theme";
    public static final String CAT_KEYS = "keys";
    public static final String CAT_DICT = "dict";
    public static final String CAT_FUNC = "func";
    public static final String CAT_PUNCT = "punct";

    public static final int FORMAT = 1;
    private static final int MAX_FILE = 512 * 1024;
    private static final int MAX_ENTRIES = 5000;

    private static final String SP_NAME = "biime_packs";
    private static final String K_THEME = "active_theme";
    private static final String K_ENABLED = "enabled_";
    private static final String K_IME_ACTIVE = "ime_last_active";

    // ------------------------------------------------------------------ 主题

    public static final class Theme {
        public final String id;
        public final String name;
        public final String desc;
        public final int panel;
        public final int toolbar;
        public final int bar;
        public final int key;
        public final int keySpecial;
        public final int accent;
        public final int text;
        public final int textDim;
        public final int english;
        public final int chip;
        public final int chipBorder;

        public Theme(String id, String name, String desc,
                     int panel, int toolbar, int bar,
                     int key, int keySpecial, int accent,
                     int text, int textDim, int english,
                     int chip, int chipBorder) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.panel = panel;
            this.toolbar = toolbar;
            this.bar = bar;
            this.key = key;
            this.keySpecial = keySpecial;
            this.accent = accent;
            this.text = text;
            this.textDim = textDim;
            this.english = english;
            this.chip = chip;
            this.chipBorder = chipBorder;
        }

        /** 从 JSON 覆盖到 base 上：缺哪个字段就沿用 base 的。 */
        Theme(String id, String name, String desc, Map<String, Object> j, Theme base) {
            this(id, name, desc,
                    Json.color(Json.str(j, "panel"), base.panel),
                    Json.color(Json.str(j, "toolbar"), base.toolbar),
                    Json.color(Json.str(j, "bar"), base.bar),
                    Json.color(Json.str(j, "key"), base.key),
                    Json.color(Json.str(j, "keySpecial"), base.keySpecial),
                    Json.color(Json.str(j, "accent"), base.accent),
                    Json.color(Json.str(j, "text"), base.text),
                    Json.color(Json.str(j, "textDim"), base.textDim),
                    Json.color(Json.str(j, "english"), base.english),
                    Json.color(Json.str(j, "chip"), base.chip),
                    Json.color(Json.str(j, "chipBorder"), base.chipBorder));
        }

        public int[] preview() {
            return new int[]{panel, key, keySpecial, accent, english};
        }
    }

    public static final Theme DEFAULT_THEME = new Theme("theme_obsidian", "曜石黑", "默认深色，夜里打字不刺眼",
            0xFF17171C, 0xFF1F1F26, 0xFF101014,
            0xFF2B2B33, 0xFF3A3A45, 0xFF2563EB,
            0xFFF2F2F7, 0xFF9A9AA8, 0xFF7FB0FF,
            0xFF23232B, 0xFF34343F);

    // ------------------------------------------------------ 键盘样式 / 功能

    /** 键盘外观参数；null = 这项不改。 */
    public static final class Keys {
        public final Integer radius;
        public final Integer rowHeight;
        public final Integer keyFont;
        public final Boolean numberRow;
        /** 自定义快捷短语行：点一下直接上屏，最多 10 个。 */
        public final List<String> keyRow;

        Keys(Integer radius, Integer rowHeight, Integer keyFont, Boolean numberRow) {
            this(radius, rowHeight, keyFont, numberRow, null);
        }

        Keys(Integer radius, Integer rowHeight, Integer keyFont, Boolean numberRow,
             List<String> keyRow) {
            this.radius = radius;
            this.rowHeight = rowHeight;
            this.keyFont = keyFont;
            this.numberRow = numberRow;
            this.keyRow = keyRow;
        }

        static Keys fromJson(Map<String, Object> j) {
            if (j == null) {
                return null;
            }
            return new Keys(Json.num(j, "keyRadius"), Json.num(j, "rowHeight"),
                    Json.num(j, "keyFont"), Json.flag(j, "numberRow"), strings(j, "keyRow", 10, 6));
        }
    }

    /** 行为开关；null = 这项不改。 */
    public static final class Func {
        public final Boolean englishOutput;
        public final Boolean traditional;
        public final Boolean spaceCommit;
        /** 中文模式下把 . , ? ! 自动转成全角 。，？！ */
        public final Boolean autoPunct;
        /** 连按两下空格，把上一个空格换成句号 */
        public final Boolean doubleSpacePeriod;
        /** 上屏文本转换：none / fullwidth / halfwidth / upper / lower */
        public final String textTransform;
        /** 模糊音（zh/z、sh/s、n/l、ang/an、ing/in…） */
        public final Boolean fuzzyPinyin;
        /** 拼错纠正（漏字母 / 相邻颠倒 / 按错键） */
        public final Boolean typoCorrect;
        /** 上屏后是否做联想 */
        public final Boolean associate;

        Func(Boolean englishOutput, Boolean traditional, Boolean spaceCommit) {
            this(englishOutput, traditional, spaceCommit, null, null, null, null, null, null);
        }

        Func(Boolean englishOutput, Boolean traditional, Boolean spaceCommit,
             Boolean autoPunct, Boolean doubleSpacePeriod, String textTransform) {
            this(englishOutput, traditional, spaceCommit, autoPunct, doubleSpacePeriod,
                    textTransform, null, null, null);
        }

        Func(Boolean englishOutput, Boolean traditional, Boolean spaceCommit,
             Boolean autoPunct, Boolean doubleSpacePeriod, String textTransform,
             Boolean fuzzyPinyin, Boolean typoCorrect, Boolean associate) {
            this.fuzzyPinyin = fuzzyPinyin;
            this.typoCorrect = typoCorrect;
            this.associate = associate;
            this.englishOutput = englishOutput;
            this.traditional = traditional;
            this.spaceCommit = spaceCommit;
            this.autoPunct = autoPunct;
            this.doubleSpacePeriod = doubleSpacePeriod;
            this.textTransform = textTransform;
        }

        static Func fromJson(Map<String, Object> j) {
            if (j == null) {
                return null;
            }
            return new Func(Json.flag(j, "englishOutput"), Json.flag(j, "traditional"),
                    Json.flag(j, "spaceCommit"), Json.flag(j, "autoPunct"),
                    Json.flag(j, "doubleSpacePeriod"), Json.str(j, "textTransform"),
                    Json.flag(j, "fuzzyPinyin"), Json.flag(j, "typoCorrect"),
                    Json.flag(j, "associate"));
        }
    }

    /** 标点集：四个常用标点键 + 三页符号位；字段为 null 表示沿用上一层的值。 */
    public static final class Punct {
        public final String comma;
        public final String period;
        public final String question;
        public final String exclaim;
        public final List<String> page1;
        public final List<String> page2;
        public final List<String> page3;

        Punct(String comma, String period, String question, String exclaim,
              List<String> page1, List<String> page2, List<String> page3) {
            this.comma = comma;
            this.period = period;
            this.question = question;
            this.exclaim = exclaim;
            this.page1 = page1;
            this.page2 = page2;
            this.page3 = page3;
        }

        static Punct fromJson(Map<String, Object> j) {
            if (j == null) {
                return null;
            }
            return new Punct(one(j, "comma"), one(j, "period"), one(j, "question"), one(j, "exclaim"),
                    strings(j, "page1", 26, 12), strings(j, "page2", 26, 12), strings(j, "page3", 26, 12));
        }

        private static String one(Map<String, Object> j, String k) {
            String v = Json.str(j, k);
            return (v == null || v.length() == 0) ? null : v;
        }

        /** 用 over 的非空字段覆盖自己。 */
        Punct merge(Punct over) {
            if (over == null) {
                return this;
            }
            return new Punct(over.comma == null ? comma : over.comma,
                    over.period == null ? period : over.period,
                    over.question == null ? question : over.question,
                    over.exclaim == null ? exclaim : over.exclaim,
                    over.page1 == null ? page1 : over.page1,
                    over.page2 == null ? page2 : over.page2,
                    over.page3 == null ? page3 : over.page3);
        }
    }

    /** 默认中文全角标点。 */
    public static final Punct DEFAULT_PUNCT = new Punct("，", "。", "？", "！", null, null, null);

    /** 合并后的最终参数：内置默认值 + 各已启用包的覆盖。 */
    public static final class Tuning {
        public final int keyRadius;
        public final int rowHeight;
        public final int keyFont;
        public final boolean numberRow;
        public final boolean englishOutput;
        public final boolean traditional;
        public final boolean spaceCommit;
        public final boolean autoPunct;
        public final boolean doubleSpacePeriod;
        public final String textTransform;
        public final Punct punct;
        public final List<String> keyRow;
        public final boolean fuzzyPinyin;
        public final boolean typoCorrect;
        public final boolean associate;

        Tuning(int keyRadius, int rowHeight, int keyFont, boolean numberRow,
               boolean englishOutput, boolean traditional, boolean spaceCommit,
               boolean autoPunct, boolean doubleSpacePeriod, String textTransform,
               Punct punct, List<String> keyRow,
               boolean fuzzyPinyin, boolean typoCorrect, boolean associate) {
            this.fuzzyPinyin = fuzzyPinyin;
            this.typoCorrect = typoCorrect;
            this.associate = associate;
            this.keyRadius = keyRadius;
            this.rowHeight = rowHeight;
            this.keyFont = keyFont;
            this.numberRow = numberRow;
            this.englishOutput = englishOutput;
            this.traditional = traditional;
            this.spaceCommit = spaceCommit;
            this.autoPunct = autoPunct;
            this.doubleSpacePeriod = doubleSpacePeriod;
            this.textTransform = textTransform;
            this.punct = punct;
            this.keyRow = keyRow;
        }
    }

    // ------------------------------------------------------------------ 词条

    public static final class Entry {
        public final String key;
        public final String chinese;
        public final String english;

        public Entry(String key, String chinese, String english) {
            this.key = key;
            this.chinese = chinese;
            this.english = english;
        }
    }

    public static final class Pack {
        public final String id;
        public final List<String> types;
        public final String icon;
        public final String name;
        public final String desc;
        public final String author;
        public final String sizeLabel;
        public final String downloads;
        public final boolean imported;
        public final Theme theme;
        public final Keys keys;
        public final Func func;
        public final List<Entry> entries;
        /** 标点集：只有 punct 类型的包才有。 */
        public Punct punct;

        Pack(String id, List<String> types, String icon, String name, String desc, String author,
             String sizeLabel, String downloads, boolean imported,
             Theme theme, Keys keys, Func func, List<Entry> entries) {
            this.id = id;
            this.types = types;
            this.icon = icon;
            this.name = name;
            this.desc = desc;
            this.author = author;
            this.sizeLabel = sizeLabel;
            this.downloads = downloads;
            this.imported = imported;
            this.theme = theme;
            this.keys = keys;
            this.func = func;
            this.entries = entries;
        }

        public boolean is(String type) {
            return types.contains(type);
        }

        public String typeLabel() {
            if (is(CAT_THEME)) {
                return "主题";
            }
            if (is(CAT_DICT)) {
                return "词典";
            }
            if (is(CAT_FUNC)) {
                return "功能";
            }
            if (is(CAT_PUNCT)) {
                return "标点";
            }
            return "键盘";
        }

        public int entryCount() {
            return entries == null ? 0 : entries.size();
        }
    }

    // ------------------------------------------------------- 内置扩展包目录

    private static List<Pack> sBuiltin;

    private static synchronized List<Pack> builtin() {
        if (sBuiltin != null) {
            return sBuiltin;
        }
        List<Pack> list = new ArrayList<Pack>();

        list.add(builtinTheme(DEFAULT_THEME));
        list.add(builtinTheme(new Theme("theme_paper", "宣纸白", "浅色键盘，白天在户外更清楚",
                0xFFEFF1F5, 0xFFE4E7ED, 0xFFFAFAFC,
                0xFFFFFFFF, 0xFFDCE0E8, 0xFF2563EB,
                0xFF15171C, 0xFF868C99, 0xFF2F63C9,
                0xFFFFFFFF, 0xFFD5DAE3)));
        list.add(builtinTheme(new Theme("theme_forest", "松林绿", "低饱和墨绿，看久了不累",
                0xFF101A16, 0xFF16221D, 0xFF0B1310,
                0xFF1E2E27, 0xFF2A3D34, 0xFF2E9E6B,
                0xFFE8F2EC, 0xFF8AA396, 0xFF6FD3A3,
                0xFF18241F, 0xFF2A3D34)));
        list.add(builtinTheme(new Theme("theme_sakura", "樱花粉", "浅粉配色，适合白天的聊天场景",
                0xFFFDF2F4, 0xFFF7E4E8, 0xFFFFF8F9,
                0xFFFFFFFF, 0xFFF2D7DD, 0xFFD9486F,
                0xFF2A1A1F, 0xFF9C7C84, 0xFFC0405F,
                0xFFFFFFFF, 0xFFF0D3D9)));
        list.add(builtinTheme(new Theme("theme_midnight", "午夜蓝", "深蓝紫，候选栏对比度最高",
                0xFF10132A, 0xFF171B38, 0xFF0A0C1D,
                0xFF1E2246, 0xFF2A2F5C, 0xFF6C5CE7,
                0xFFE9EAFB, 0xFF8B8FBF, 0xFF9C8CFF,
                0xFF181C3A, 0xFF2A2F5C)));

        list.add(builtinKeys("keys_compact", "紧凑键盘", "键更矮、字更小，一屏能多看一行字",
                new Keys(Integer.valueOf(7), Integer.valueOf(40), Integer.valueOf(17), Boolean.FALSE)));
        list.add(builtinKeys("keys_large", "大字键盘", "键更高、字更大，手大或视力吃力时好用",
                new Keys(Integer.valueOf(12), Integer.valueOf(54), Integer.valueOf(22), Boolean.FALSE)));
        list.add(builtinKeys("keys_number", "带数字行", "键盘上方常驻一行数字，不用切符号页",
                new Keys(Integer.valueOf(9), Integer.valueOf(42), Integer.valueOf(18), Boolean.TRUE)));

        list.add(new Pack("dict_greeting", one(CAT_DICT), "💬", "日常问候",
                "寒暄、道别、关心人的常用说法，带英文对照", "BiIme 内置", "内置", "内置", false,
                null, null, null, entries(
                        "haijiubujian", "好久不见", "long time no see",
                        "chilema", "吃了吗", "have you eaten? (a greeting)",
                        "xinkule", "辛苦了", "thanks for your hard work",
                        "lushangxiaoxin", "路上小心", "take care on the road",
                        "baozhong", "保重", "take care of yourself",
                        "zaodianxiuxi", "早点休息", "go to bed early",
                        "zhoumoyukuai", "周末愉快", "have a nice weekend",
                        "jiayou", "加油", "come on! you can do it")));

        list.add(new Pack("dict_work", one(CAT_DICT), "💼", "办公沟通",
                "确认、催办、发文件时最常用的几句", "BiIme 内置", "内置", "内置", false,
                null, null, null, entries(
                        "shoudao", "收到", "received, got it",
                        "qingchashou", "请查收", "please check (the attachment)",
                        "shaodeng", "稍等", "one moment please",
                        "huiyijiyao", "会议纪要", "meeting minutes",
                        "fujianyifa", "附件已发", "attachment sent",
                        "mafannile", "麻烦你了", "sorry to trouble you",
                        "woquerenyixia", "我确认一下", "let me confirm",
                        "jinkuaihuifu", "尽快回复", "please reply as soon as possible")));

        list.add(new Pack("dict_study", one(CAT_DICT), "📚", "校园学习",
                "选课、作业、考试场景的高频词", "BiIme 内置", "内置", "内置", false,
                null, null, null, entries(
                        "jiaozuoye", "交作业", "hand in homework",
                        "qimokaoshi", "期末考试", "final exam",
                        "fuxi", "复习", "to review (lessons)",
                        "lunwendabian", "论文答辩", "thesis defense",
                        "xuanke", "选课", "to choose courses",
                        "chengjidan", "成绩单", "transcript",
                        "tushuguan", "图书馆", "library",
                        "biji", "笔记", "notes")));

        list.add(new Pack("func_english", one(CAT_FUNC), "🔤", "英文优先",
                "键盘一弹出就是「输出：英文」模式，点候选直接出英文", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(Boolean.TRUE, null, null), null));

        list.add(new Pack("func_traditional", one(CAT_FUNC), "🀄", "繁体输出",
                "默认上屏繁体字形，随时能用工具条切回简体", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, Boolean.TRUE, null), null));

        list.add(new Pack("func_space", one(CAT_FUNC), "␣", "空格只打空格",
                "关掉「空格上屏首选词」，空格就只负责插入空格", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, null, Boolean.FALSE), null));

        list.add(punctPack("punct_halfwidth", "🔡", "半角标点",
                "标点全用英文半角，写代码、写英文时更顺手",
                new Punct(",", ".", "?", "!", null, null, null)));

        list.add(punctPack("punct_rich", "🔣", "标点加强",
                "三页符号都补满 26 格：中文标点 / 数学 / 单位与序号",
                new Punct(null, null, null, null,
                        list("、", "。", "，", "？", "！", "：", "；", "“", "”", "…",
                             "‘", "’", "（", "）", "《", "》", "【", "】", "—",
                             "·", "～", "〈", "〉", "「", "」", "『"),
                        list("+", "−", "×", "÷", "=", "≠", "≈", "≤", "≥", "±",
                             "√", "∞", "π", "‰", "%", "∑", "∫", "∠", "⊥",
                             "∈", "∪", "∩", "∅", "∝", "∴", "∵"),
                        list("№", "℃", "℉", "Å", "µ", "Ω", "㎜", "㎝", "㎞", "㎎",
                             "㎏", "㎡", "㎥", "㈠", "㈡", "㈢", "①", "②", "③",
                             "④", "⑤", "⑴", "⑵", "Ⅰ", "Ⅱ", "Ⅲ"))));

        list.add(punctPack("punct_math", "➗", "数学符号页",
                "第一页直接换成数学符号，写公式不用来回找",
                new Punct(null, null, null, null,
                        list("+", "−", "×", "÷", "=", "≠", "≈", "≤", "≥", "±",
                             "√", "∞", "π", "‰", "%", "∑", "∫", "∠", "⊥",
                             "∈", "∪", "∩", "∅", "∝", "∴", "∵"),
                        null, null)));

        list.add(new Pack("func_autopunct", one(CAT_FUNC), "✒️", "自动全角标点",
                "中文模式下按 . , ? ! 自动变成 。，？！", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, null, null, Boolean.TRUE, null, null), null));

        list.add(new Pack("func_doublespace", one(CAT_FUNC), "␣␣", "双击空格出句号",
                "连按两下空格，把上一个空格换成句号，少切一次符号页", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, null, null, null, Boolean.TRUE, null), null));

        list.add(new Pack("func_fullwidth", one(CAT_FUNC), "🅰", "全角输出",
                "上屏的字母数字都转成全角，适合写正式排版", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, null, null, null, null, "fullwidth"), null));

        list.add(new Pack("func_strict", one(CAT_FUNC), "🎯", "严格拼音",
                "关掉模糊音和拼错纠正，只认打得完全准的拼音", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, null, null, null, null, null,
                        Boolean.FALSE, Boolean.FALSE, null), null));

        list.add(new Pack("func_noassoc", one(CAT_FUNC), "🚫", "关闭联想",
                "上屏之后不再接着推荐下一个词", "BiIme 内置", "内置", "内置", false,
                null, null, new Func(null, null, null, null, null, null,
                        null, null, Boolean.FALSE), null));

        list.add(new Pack("keys_reply", one(CAT_KEYS), "💬", "常用回复行",
                "键盘上方多一行「好的 / 收到 / 稍等」，点一下就上屏", "BiIme 内置", "内置", "内置", false,
                null, new Keys(null, null, null, null,
                        list("好的", "收到", "稍等", "马上", "谢谢", "辛苦了")), null, null));

        sBuiltin = list;
        return sBuiltin;
    }

    private static List<String> one(String type) {
        List<String> l = new ArrayList<String>();
        l.add(type);
        return l;
    }

    private static Pack builtinTheme(Theme t) {
        return new Pack(t.id, one(CAT_THEME), "🎨", t.name, t.desc, "BiIme 内置",
                "内置", "内置", false, t, null, null, null);
    }

    private static Pack builtinKeys(String id, String name, String desc, Keys k) {
        return new Pack(id, one(CAT_KEYS), "⌨️", name, desc, "BiIme 内置",
                "内置", "内置", false, null, k, null, null);
    }

    /** 从 JSON 数组里取字符串列表，限制个数与单条长度。 */
    private static List<String> strings(Map<String, Object> j, String key, int maxCount, int maxLen) {
        List<Object> arr = Json.arr(j, key);
        if (arr == null) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.size() && out.size() < maxCount; i++) {
            if (arr.get(i) instanceof String) {
                String s = (String) arr.get(i);
                if (s.length() > 0 && s.length() <= maxLen) {
                    out.add(s);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> list(String... items) {
        List<String> l = new ArrayList<String>();
        for (int i = 0; i < items.length; i++) {
            l.add(items[i]);
        }
        return l;
    }

    private static Pack punctPack(String id, String icon, String name, String desc, Punct punct) {
        Pack p = new Pack(id, one(CAT_PUNCT), icon, name, desc, "BiIme 内置", "内置", "内置", false,
                null, null, null, null);
        p.punct = punct;
        return p;
    }

    private static List<Entry> entries(String... flat) {
        List<Entry> list = new ArrayList<Entry>();
        for (int i = 0; i + 2 < flat.length; i += 3) {
            list.add(new Entry(flat[i], flat[i + 1], flat[i + 2]));
        }
        return list;
    }

    // ------------------------------------------------------------ 导入的包

    private static List<Pack> sImported;

    private static File packDir(Context c) {
        File d = new File(c.getFilesDir(), "packs");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private static synchronized List<Pack> imported(Context c) {
        if (sImported != null) {
            return sImported;
        }
        List<Pack> list = new ArrayList<Pack>();
        File[] files = packDir(c).listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (!f.getName().endsWith(".json")) {
                    continue;
                }
                try {
                    Pack p = parse(readText(new FileInputStream(f)), f);
                    if (p != null) {
                        list.add(p);
                    }
                } catch (Throwable ignored) {
                    // 坏掉的包忽略，不影响其它包
                }
            }
        }
        Collections.sort(list, new Comparator<Pack>() {
            public int compare(Pack a, Pack b) {
                return a.name.compareTo(b.name);
            }
        });
        sImported = list;
        return sImported;
    }

    public static void invalidate() {
        sImported = null;
    }

    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 全量目录：内置在前，导入的在后（参数合并时后面的覆盖前面的）。 */
    public static List<Pack> catalog(Context c) {
        List<Pack> all = new ArrayList<Pack>(builtin());
        all.addAll(imported(c));
        return all;
    }

    public static List<Pack> byCategory(Context c, String category) {
        List<Pack> all = catalog(c);
        if (CAT_ALL.equals(category)) {
            return all;
        }
        List<Pack> out = new ArrayList<Pack>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).is(category)) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    public static Pack byId(Context c, String id) {
        List<Pack> all = catalog(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                return all.get(i);
            }
        }
        return null;
    }

    public static boolean isBuiltinId(String id) {
        List<Pack> all = builtin();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 解析校验

    private static Pack parse(String text, File file) {
        Map<String, Object> m = Json.obj(Json.parse(text));
        if (m == null) {
            return null;
        }
        Integer fmt = Json.num(m, "biime_pack");
        if (fmt == null || fmt.intValue() != FORMAT) {
            return null;
        }
        String id = Json.str(m, "id");
        String name = Json.str(m, "name");
        if (id == null || name == null) {
            return null;
        }
        List<String> types = typesOf(m);
        if (types.isEmpty()) {
            return null;
        }
        Theme theme = null;
        if (types.contains(CAT_THEME)) {
            Map<String, Object> tj = Json.obj(m.get("theme"));
            theme = new Theme(id, name, str(m, "desc", ""),
                    tj == null ? new LinkedHashMap<String, Object>() : tj, DEFAULT_THEME);
        }
        Keys keys = types.contains(CAT_KEYS) ? Keys.fromJson(Json.obj(m.get("keys"))) : null;
        Func func = types.contains(CAT_FUNC) ? Func.fromJson(Json.obj(m.get("func"))) : null;
        Punct punct = types.contains(CAT_PUNCT) ? Punct.fromJson(Json.obj(m.get("punct"))) : null;
        List<Entry> es = types.contains(CAT_DICT) ? entriesOf(m) : null;

        long size = file == null ? 0 : file.length();
        Pack p = new Pack(id, types, iconFor(types), name, str(m, "desc", ""),
                str(m, "author", "本地导入"), sizeLabel(size), "导入", true,
                theme, keys, func, es);
        p.punct = punct;
        return p;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        String v = Json.str(m, key);
        return (v == null || v.trim().length() == 0) ? def : v.trim();
    }

    private static List<String> typesOf(Map<String, Object> m) {
        List<String> out = new ArrayList<String>();
        List<Object> arr = Json.arr(m, "types");
        if (arr == null) {
            arr = Json.arr(m, "type");   // 也允许写成 "type": ["keys","func"]
        }
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i) instanceof String) {
                    String t = (String) arr.get(i);
                    if (isValidType(t) && !out.contains(t)) {
                        out.add(t);
                    }
                }
            }
        } else {
            String t = Json.str(m, "type");
            if (t != null && isValidType(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean isValidType(String t) {
        return CAT_THEME.equals(t) || CAT_KEYS.equals(t) || CAT_DICT.equals(t)
                || CAT_FUNC.equals(t) || CAT_PUNCT.equals(t);
    }

    private static List<Entry> entriesOf(Map<String, Object> m) {
        List<Entry> out = new ArrayList<Entry>();
        List<Object> arr = Json.arr(m, "dict");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.size() && out.size() < MAX_ENTRIES; i++) {
            Map<String, Object> e = Json.obj(arr.get(i));
            if (e == null) {
                continue;
            }
            String key = norm(Json.str(e, "pinyin"));
            String word = Json.str(e, "word");
            if (key == null || key.length() == 0 || word == null || word.trim().length() == 0) {
                continue;
            }
            out.add(new Entry(key, word.trim(), str(e, "en", "")));
        }
        return out;
    }

    /** 拼音键统一成小写无声调 a-z（ü 写成 v）。 */
    private static String norm(String pinyin) {
        if (pinyin == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String lower = pinyin.toLowerCase().replace("ü", "v").replace("u:", "v");
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String iconFor(List<String> types) {
        if (types.contains(CAT_THEME)) {
            return "🎨";
        }
        if (types.contains(CAT_DICT)) {
            return "📖";
        }
        if (types.contains(CAT_FUNC)) {
            return "⚙️";
        }
        if (types.contains(CAT_PUNCT)) {
            return "🔣";
        }
        return "⌨️";
    }

    private static String sizeLabel(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return (bytes / 1024) + " KB";
    }

    // ------------------------------------------------------------ 导入 / 删除

    /**
     * 导入一个扩展包，返回导入后的包。
     * 校验失败抛 IllegalArgumentException，消息可以直接显示给用户。
     */
    public static Pack importPack(Context c, String text) {
        if (text == null || text.trim().length() == 0) {
            throw new IllegalArgumentException("内容为空");
        }
        if (text.length() > MAX_FILE) {
            throw new IllegalArgumentException("文件太大，上限 512 KB");
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (Json.Error e) {
            throw new IllegalArgumentException("不是合法的 JSON：" + e.getMessage());
        }
        Map<String, Object> m = Json.obj(root);
        if (m == null) {
            throw new IllegalArgumentException("扩展包必须是一个 JSON 对象");
        }
        Integer fmt = Json.num(m, "biime_pack");
        if (fmt == null) {
            throw new IllegalArgumentException("缺少 biime_pack 版本号字段");
        }
        if (fmt.intValue() != FORMAT) {
            throw new IllegalArgumentException("不支持的版本号 " + fmt + "（本应用支持 " + FORMAT + "）");
        }
        String id = Json.str(m, "id");
        if (id == null || id.trim().length() == 0) {
            throw new IllegalArgumentException("缺少 id");
        }
        id = id.trim();
        if (id.length() > 64) {
            throw new IllegalArgumentException("id 太长");
        }
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            if (!ok) {
                throw new IllegalArgumentException("id 只能包含字母数字和 . _ -");
            }
        }
        if (isBuiltinId(id)) {
            throw new IllegalArgumentException("id 与内置包冲突：" + id);
        }
        String name = Json.str(m, "name");
        if (name == null || name.trim().length() == 0) {
            throw new IllegalArgumentException("缺少 name");
        }
        List<String> types = typesOf(m);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("缺少 type（可选：theme / keys / dict / func / punct）");
        }
        if (types.contains(CAT_DICT) && entriesOf(m).isEmpty()) {
            throw new IllegalArgumentException("type 写了 dict，但 dict 词条是空的");
        }
        if (types.contains(CAT_THEME) && Json.obj(m.get("theme")) == null) {
            throw new IllegalArgumentException("type 写了 theme，但缺少 theme 配色对象");
        }
        if (types.contains(CAT_KEYS) && Json.obj(m.get("keys")) == null) {
            throw new IllegalArgumentException("type 写了 keys，但缺少 keys 对象");
        }
        if (types.contains(CAT_FUNC) && Json.obj(m.get("func")) == null) {
            throw new IllegalArgumentException("type 写了 func，但缺少 func 对象");
        }
        if (types.contains(CAT_PUNCT) && Json.obj(m.get("punct")) == null) {
            throw new IllegalArgumentException("type 写了 punct，但缺少 punct 对象");
        }

        try {
            FileOutputStream out = new FileOutputStream(new File(packDir(c), id + ".json"));
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            throw new IllegalArgumentException("保存失败：" + e.getMessage());
        }
        invalidate();
        setEnabled(c, id, true);
        return byId(c, id);
    }

    public static void deletePack(Context c, String id) {
        File f = new File(packDir(c), id + ".json");
        if (f.exists()) {
            f.delete();
        }
        setEnabled(c, id, false);
        if (id.equals(activeThemeId(c))) {
            setActiveTheme(c, DEFAULT_THEME.id);
        }
        invalidate();
    }

    // -------------------------------------------------------------- 状态存取

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static String activeThemeId(Context c) {
        return sp(c).getString(K_THEME, DEFAULT_THEME.id);
    }

    public static Theme activeTheme(Context c) {
        String id = activeThemeId(c);
        List<Pack> all = catalog(c);
        for (int i = 0; i < all.size(); i++) {
            Pack p = all.get(i);
            if (p.id.equals(id) && p.theme != null) {
                return p.theme;
            }
        }
        return DEFAULT_THEME;
    }

    public static void setActiveTheme(Context c, String themeId) {
        sp(c).edit().putString(K_THEME, themeId).apply();
    }

    public static boolean isEnabled(Context c, String packId) {
        return sp(c).getBoolean(K_ENABLED + packId, false);
    }

    public static void setEnabled(Context c, String packId, boolean on) {
        sp(c).edit().putBoolean(K_ENABLED + packId, on).apply();
    }

    /** 全部已启用包贡献的词条。 */
    public static List<Entry> enabledEntries(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        List<Pack> all = catalog(c);
        for (int i = 0; i < all.size(); i++) {
            Pack p = all.get(i);
            if (p.entries != null && isEnabled(c, p.id)) {
                out.addAll(p.entries);
            }
        }
        return out;
    }

    /** 合并键盘参数：从内置默认值开始，按目录顺序让每个已启用包覆盖。 */
    public static Tuning tuning(Context c) {
        int radius = 9;
        int rowHeight = 44;
        int keyFont = 19;
        boolean numberRow = false;
        boolean englishOutput = false;
        boolean traditional = false;
        boolean spaceCommit = true;
        boolean autoPunct = false;
        boolean doubleSpacePeriod = false;
        String textTransform = "none";
        Punct punct = DEFAULT_PUNCT;
        List<String> keyRow = null;
        boolean fuzzyPinyin = true;
        boolean typoCorrect = true;
        boolean associate = true;

        List<Pack> all = catalog(c);
        for (int i = 0; i < all.size(); i++) {
            Pack p = all.get(i);
            if (!isEnabled(c, p.id)) {
                continue;
            }
            if (p.keys != null) {
                if (p.keys.radius != null) {
                    radius = clamp(p.keys.radius.intValue(), 0, 24);
                }
                if (p.keys.rowHeight != null) {
                    rowHeight = clamp(p.keys.rowHeight.intValue(), 32, 72);
                }
                if (p.keys.keyFont != null) {
                    keyFont = clamp(p.keys.keyFont.intValue(), 12, 30);
                }
                if (p.keys.numberRow != null) {
                    numberRow = p.keys.numberRow.booleanValue();
                }
                if (p.keys.keyRow != null) {
                    keyRow = p.keys.keyRow;
                }
            }
            if (p.func != null) {
                if (p.func.englishOutput != null) {
                    englishOutput = p.func.englishOutput.booleanValue();
                }
                if (p.func.traditional != null) {
                    traditional = p.func.traditional.booleanValue();
                }
                if (p.func.spaceCommit != null) {
                    spaceCommit = p.func.spaceCommit.booleanValue();
                }
                if (p.func.autoPunct != null) {
                    autoPunct = p.func.autoPunct.booleanValue();
                }
                if (p.func.doubleSpacePeriod != null) {
                    doubleSpacePeriod = p.func.doubleSpacePeriod.booleanValue();
                }
                if (p.func.textTransform != null) {
                    textTransform = p.func.textTransform;
                }
                if (p.func.fuzzyPinyin != null) {
                    fuzzyPinyin = p.func.fuzzyPinyin.booleanValue();
                }
                if (p.func.typoCorrect != null) {
                    typoCorrect = p.func.typoCorrect.booleanValue();
                }
                if (p.func.associate != null) {
                    associate = p.func.associate.booleanValue();
                }
            }
            if (p.punct != null) {
                punct = punct.merge(p.punct);
            }
        }
        return new Tuning(radius, rowHeight, keyFont, numberRow, englishOutput, traditional,
                spaceCommit, autoPunct, doubleSpacePeriod, textTransform, punct, keyRow,
                fuzzyPinyin, typoCorrect, associate);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 界面上的小状态（比如扩展包那一块是展开还是收起）。 */
    public static boolean uiFlag(Context c, String key, boolean def) {
        return sp(c).getBoolean("ui_" + key, def);
    }

    public static void setUiFlag(Context c, String key, boolean value) {
        sp(c).edit().putBoolean("ui_" + key, value).apply();
    }

    /**
     * 输入法每次弹出时留个时间戳。
     * Android 14 起 targetSdk 34 的应用读不到 Settings.Secure.DEFAULT_INPUT_METHOD，
     * 首页就用这个心跳判断「最近是不是在用本输入法」。
     */
    public static void markImeActive(Context c) {
        sp(c).edit().putLong(K_IME_ACTIVE, System.currentTimeMillis()).apply();
    }

    public static long imeLastActive(Context c) {
        return sp(c).getLong(K_IME_ACTIVE, 0L);
    }

    /** 某个包当前的状态文案。 */
    public static String stateLabel(Context c, Pack p) {
        if (p.is(CAT_THEME)) {
            return p.id.equals(activeThemeId(c)) ? "使用中" : "点击使用";
        }
        return isEnabled(c, p.id) ? "已启用" : "点击启用";
    }
}
