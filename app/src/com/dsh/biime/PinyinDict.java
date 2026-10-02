package com.dsh.biime;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线拼音词典（数据源 CC-CEDICT，CC BY-SA 4.0）。
 *
 * assets/pinyin.dict 每行 7 个制表符分隔字段，按第 1 列字典序排序：
 * key \t 简体 \t 繁体 \t 带声调拼音 \t 英文释义 \t 音节数 \t 词频分
 * key 为无声调小写拼音（ü 记作 v）。
 *
 * 整个文件以 UTF-8 字节数组常驻内存，用 int 偏移索引行，避免 10 万个 String 对象。
 */
public final class PinyinDict {

    private static final String TAG = "PinyinDict";
    private static final String ASSET = "pinyin.dict";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 整句切分时向前回看的最大拼音长度（拼音字母数，不是音节数）。 */
    private static final int MAX_WINDOW = 20;
    /** 单个音节最长的拼音长度："zhuang"/"chuang"/"shuang" 都是 6 个字母。 */
    private static final int MAX_SYL_LEN = 6;
    /** 整句切分时，一个词多覆盖一个字额外奖励 WORD_BONUS（相当于“成词”概率高 80 倍）。 */
    private static final double WORD_BONUS = Math.log(80.0);
    /** 每多切一刀的惩罚，防止把 hao 拆成 ha+o 这种“切得更碎反而分更高”。 */
    private static final double PIECE_PENALTY = Math.log(1000.0);

    private static volatile PinyinDict sInstance;

    private final byte[] data;
    private final int[] off;
    private final int[] len;
    private final short[] keyLen;
    private final int lineCount;
    private final Set<String> syllables;
    private volatile int[] charFreqTable;

    private static final Comparator<Candidate> BY_RANK = new Comparator<Candidate>() {
        public int compare(Candidate a, Candidate b) {
            if (a.rank != b.rank) {
                return a.rank < b.rank ? -1 : 1;
            }
            int la = a.simp.length();
            int lb = b.simp.length();
            if (la != lb) {
                return la < lb ? -1 : 1;
            }
            return a.simp.compareTo(b.simp);
        }
    };

    private PinyinDict(byte[] data, int[] off, int[] len, short[] keyLen, int lineCount,
                       Set<String> syllables) {
        this.data = data;
        this.off = off;
        this.len = len;
        this.keyLen = keyLen;
        this.lineCount = lineCount;
        this.syllables = syllables;
    }

    /** 单例；首次调用会做一次同步加载（调用方请放在后台线程）。 */
    public static PinyinDict get(Context context) {
        PinyinDict d = sInstance;
        if (d == null) {
            synchronized (PinyinDict.class) {
                d = sInstance;
                if (d == null) {
                    d = load(context.getApplicationContext());
                    sInstance = d;
                }
            }
        }
        return d;
    }

    private static byte[] readAsset(Context context, String name) {
        InputStream in = null;
        try {
            in = context.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 22);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "读取词典失败: " + name, e);
            return new byte[0];
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static PinyinDict load(Context context) {
        long t0 = System.currentTimeMillis();
        byte[] data = readAsset(context, ASSET);
        int lines = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines++;
            }
        }
        int[] off = new int[lines + 1];
        int[] len = new int[lines + 1];
        short[] keyLen = new short[lines + 1];
        int idx = 0;
        int start = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                if (i > start && idx < off.length) {
                    off[idx] = start;
                    len[idx] = i - start;
                    int p = start;
                    while (p < i && data[p] != '\t') {
                        p++;
                    }
                    keyLen[idx] = (short) (p - start);
                    idx++;
                }
                start = i + 1;
            }
        }

        Set<String> syllables = new HashSet<String>();
        for (int i = 0; i < idx; i++) {
            // 行尾两列是「音节数」和「词频分」，倒数第 2 列为 '1' 说明这是单音节词（即一个汉字）
            int ls = off[i];
            int end = off[i] + len[i];
            // 从行尾往前找「词频分」前的制表符
            int p = end - 1;
            while (p >= ls && data[p] != '\t') {
                p--;
            }
            int nsylEnd = p;        // 音节数字段结束于该制表符之前
            int q = p - 1;
            while (q >= ls && data[q] != '\t') {
                q--;
            }
            int nsylStart = q + 1;  // 音节数字段起点
            if (nsylEnd - nsylStart == 1 && data[nsylStart] == '1' && keyLen[i] > 0) {
                syllables.add(new String(data, ls, keyLen[i], UTF8));
            }
        }

        PinyinDict d = new PinyinDict(data, off, len, keyLen, idx, syllables);
        d.charFreqTable();   // 在后台线程里预热汉字词频表，别拖到第一次按键
        Log.i(TAG, "词典加载 " + idx + " 条，音节 " + syllables.size()
                + " 个，耗时 " + (System.currentTimeMillis() - t0) + "ms");
        return d;
    }

    // ------------------------------------------------------------------ 查找

    private int compareKey(int i, String key) {
        int p = off[i];
        int kl = keyLen[i];
        int n = Math.min(kl, key.length());
        for (int j = 0; j < n; j++) {
            int a = data[p + j] & 0xff;
            int b = key.charAt(j) & 0xffff;
            if (a != b) {
                return a - b;
            }
        }
        return kl - key.length();
    }

    private boolean keyStartsWith(int i, String prefix) {
        int kl = keyLen[i];
        if (kl < prefix.length()) {
            return false;
        }
        int p = off[i];
        for (int j = 0; j < prefix.length(); j++) {
            if ((data[p + j] & 0xff) != (prefix.charAt(j) & 0xffff)) {
                return false;
            }
        }
        return true;
    }

    private int lowerBound(String key) {
        int lo = 0;
        int hi = lineCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareKey(mid, key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private Candidate parse(int i) {
        String[] f = new String[7];
        int fi = 0;
        int s = off[i];
        int e = off[i] + len[i];
        for (int q = s; q <= e && fi < 7; q++) {
            if (q == e || data[q] == '\t') {
                f[fi++] = new String(data, s, q - s, UTF8);
                s = q + 1;
            }
        }
        String simp = fi > 1 ? f[1] : "";
        String trad = fi > 2 ? f[2] : simp;
        String pinyin = fi > 3 ? f[3] : "";
        String gloss = fi > 4 ? f[4] : "";
        int rank = 9999;
        if (fi > 6) {
            try {
                rank = Integer.parseInt(f[6].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String word = firstSense(gloss);
        return new Candidate(simp, trad, pinyin, gloss, word, null, keyLen[i], rank);
    }

    /** 从 CC-CEDICT 释义 "/hello/hi/CL:聲|声[sheng1]/" 里取第一个义项。 */
    public static String firstSense(String gloss) {
        if (gloss == null || gloss.length() == 0) {
            return "";
        }
        String[] parts = gloss.split("/");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() == 0 || p.startsWith("CL:") || p.startsWith("see ")) {
                continue;
            }
            int semi = p.indexOf(';');
            if (semi > 0) {
                p = p.substring(0, semi).trim();
            }
            if (p.length() > 0) {
                return p;
            }
        }
        return "";
    }

    /** 前缀匹配（词的拼音比输入更长）的分数惩罚，避免它挤掉同样常用的精确匹配。 */
    private static final int PREFIX_PENALTY = 250000;

    /**
     * 拼音前缀查找：把精确匹配（整串拼音恰好是一个词）和前缀匹配（更长的词）放在一起，
     * 按「词频分 + 前缀惩罚」排序。例如输入 ni 时“你”排在“你好”“尼”之前，
     * 输入 nihao 时“你好”排第一。
     */
    public List<Candidate> search(String input, int max) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (input == null || input.length() == 0 || lineCount == 0) {
            return out;
        }
        final String key = input.toLowerCase();
        final int inLen = key.length();
        List<Candidate> all = new ArrayList<Candidate>();
        for (int i = lowerBound(key); i < lineCount; i++) {
            if (!keyStartsWith(i, key)) {
                break;
            }
            all.add(parse(i));
        }
        Collections.sort(all, new Comparator<Candidate>() {
            public int compare(Candidate a, Candidate b) {
                int sa = a.rank + (a.keyLen == inLen ? 0 : PREFIX_PENALTY);
                int sb = b.rank + (b.keyLen == inLen ? 0 : PREFIX_PENALTY);
                if (sa != sb) {
                    return sa < sb ? -1 : 1;
                }
                int la = a.simp.length();
                int lb = b.simp.length();
                if (la != lb) {
                    return la < lb ? -1 : 1;
                }
                return a.simp.compareTo(b.simp);
            }
        });
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < all.size() && out.size() < max; i++) {
            Candidate c = all.get(i);
            // 同一个汉字只保留排名最好的那一条（多音字/异体字会重复）
            if (seen.add(c.simp)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 词典里 key 恰好等于该串的最佳词条；没有返回 null。 */
    private Candidate bestExact(String key) {
        Candidate best = null;
        for (int i = lowerBound(key); i < lineCount; i++) {
            if (!keyStartsWith(i, key)) {
                break;
            }
            if (keyLen[i] != key.length()) {
                continue;
            }
            Candidate c = parse(i);
            if (best == null || c.rank < best.rank) {
                best = c;
            }
        }
        return best;
    }

    /** 某个音节最常用的单字词条（用于整串查不到时的兜底切分）。 */
    private Candidate topCharCandidate(String syllable) {
        Candidate best = null;
        for (int i = lowerBound(syllable); i < lineCount; i++) {
            if (!keyStartsWith(i, syllable)) {
                break;
            }
            if (keyLen[i] != syllable.length()) {
                continue;
            }
            Candidate c = parse(i);
            if (c.simp.length() != 1) {
                continue;
            }
            if (best == null || c.rank < best.rank) {
                best = c;
            }
        }
        return best;
    }

    private static int scoreOf(int rank) {
        int freq = 2000000 - (rank > 2000000 ? 2000000 : rank);
        return freq > 1 ? freq : 1;
    }

    /**
     * 兜底整句切分：把输入切成「词典里真实存在的词」的最优组合。
     * 目标是分词数最少，其次总词频最高——所以
     * xiexienin → 谢谢+您，woshiyigexuesheng → 我+是+一个+学生，
     * 而不是逐字硬凑。全部切不出来时返回 null。
     */
    public String segment(String input, boolean traditional) {
        List<Candidate> pieces = segmentPieces(input, traditional);
        if (pieces == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            sb.append(pieces.get(i).chinese(traditional));
        }
        return sb.toString();
    }

    /**
     * 兜底整句切分的候选形式：英文用各段释义拼出来，例如
     * woxihuanni → 我喜欢你 / "I + to like + you"。
     */
    public Candidate segmentCandidate(String input, boolean traditional) {
        List<Candidate> pieces = segmentPieces(input, traditional);
        if (pieces == null) {
            return null;
        }
        StringBuilder zh = new StringBuilder();
        StringBuilder en = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            Candidate c = pieces.get(i);
            zh.append(c.chinese(traditional));
            String e = c.englishWord;
            if (e != null && e.length() > 0) {
                if (en.length() > 0) {
                    en.append(" + ");
                }
                en.append(e);
            }
        }
        String english = en.length() > 0 ? en.toString() : "逐字拼合";
        if (english.length() > 56) {
            english = english.substring(0, 55) + "…";
        }
        return new Candidate(zh.toString(), zh.toString(), input, english, "", null,
                input.length(), Integer.MAX_VALUE - 1);
    }

    /**
     * 用「字频 + 成词奖励」给一段切分打分：逐字读的分数是各字词频之和，
     * 一个词把多个字连起来读则额外加成词奖励。于是
     * woaini 选 我+爱+你（三个高频字）而不是词典里的冷僻词 爱昵，
     * nihaoma 选 你好+吗 而不是 你+号码。
     */
    private double pieceScore(Candidate c, boolean traditional, int[] table) {
        String zh = c.chinese(traditional);
        double s = 0;
        for (int k = 0; k < zh.length(); k++) {
            char ch = zh.charAt(k);
            int f = ch < table.length ? table[ch] : 0;
            s += Math.log(f + 1);
        }
        if (zh.length() > 1) {
            s += WORD_BONUS * (zh.length() - 1);
        }
        return s;
    }

    private List<Candidate> segmentPieces(String input, boolean traditional) {
        if (input == null || input.length() == 0) {
            return null;
        }
        int n = input.length();

        // 第一步：按「最长匹配」切出音节的合法边界，之后只允许在音节边界上组词。
        // 否则 qian 会被切成 qi+an（其按）、huan 会被切成 hu+an（湖按）这种
        // 音节合法但根本不是原意的读法。
        boolean[] cut = new boolean[n + 1];
        cut[0] = true;
        int pos = 0;
        while (pos < n) {
            int hit = -1;
            int maxLen = Math.min(MAX_SYL_LEN, n - pos);
            for (int l = maxLen; l >= 1; l--) {
                if (syllables.contains(input.substring(pos, pos + l))) {
                    hit = l;
                    break;
                }
            }
            if (hit < 0) {
                return null;    // 不是合法拼音串
            }
            pos += hit;
            cut[pos] = true;
        }

        double[] best = new double[n + 1];
        int[] cnt = new int[n + 1];
        int[] from = new int[n + 1];
        Candidate[] pick = new Candidate[n + 1];
        int[] table = charFreqTable();
        for (int i = 0; i <= n; i++) {
            best[i] = -1.0;
            from[i] = -1;
        }
        best[0] = 0.0;

        for (int i = 1; i <= n; i++) {
            if (!cut[i]) {
                continue;
            }
            for (int j = Math.max(0, i - MAX_WINDOW); j < i; j++) {
                if (!cut[j] || best[j] < 0) {
                    continue;
                }
                String w = input.substring(j, i);
                Candidate c = bestExact(w);
                if (c == null) {
                    // 不是词典里的词，那就只能是单个合法音节，退化成逐字
                    if (!syllables.contains(w)) {
                        continue;
                    }
                    c = topCharCandidate(w);
                    if (c == null) {
                        continue;
                    }
                }
                double s = best[j] + pieceScore(c, traditional, table);
                if (j > 0) {
                    s -= PIECE_PENALTY;
                }
                int nc = cnt[j] + 1;
                if (s > best[i] || (s == best[i] && nc < cnt[i])) {
                    best[i] = s;
                    cnt[i] = nc;
                    from[i] = j;
                    pick[i] = c;
                }
            }
        }
        if (best[n] < 0) {
            return null;
        }
        List<Candidate> pieces = new ArrayList<Candidate>();
        int i = n;
        while (i > 0 && from[i] >= 0) {
            pieces.add(0, pick[i]);
            i = from[i];
        }
        return pieces;
    }

    // -------------------------------------------------- 拼音校正（模糊音 / 拼错）

    /** 模糊音规则；n/l、f/h 这类单字母只在词首替换，避免 an→al 这种荒唐结果。 */
    private static final String[][] FUZZY = {
            {"zh", "z"}, {"ch", "c"}, {"sh", "s"},
            {"n", "l"}, {"l", "n"}, {"f", "h"},
            {"ang", "an"}, {"eng", "en"}, {"ing", "in"}, {"ong", "on"},
            {"iang", "ian"}, {"uang", "uan"}, {"ian", "iang"}, {"uan", "uang"},
    };

    private static void addVariant(List<String> out, Set<String> seen, String s,
                                   String from, String to) {
        int idx = s.indexOf(from);
        if (idx < 0) {
            return;
        }
        if (from.length() == 1 && idx != 0) {
            return;
        }
        String v = s.substring(0, idx) + to + s.substring(idx + from.length());
        if (v.length() > 0 && seen.add(v)) {
            out.add(v);
        }
    }

    /** 模糊音候选写法（不含原串），最多 max 个。 */
    private static String[] fuzzyVariants(String key, int max) {
        List<String> all = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        all.add(key);
        seen.add(key);
        for (int i = 0; i < FUZZY.length; i++) {
            addVariant(all, seen, key, FUZZY[i][0], FUZZY[i][1]);
            addVariant(all, seen, key, FUZZY[i][1], FUZZY[i][0]);
        }
        int base = all.size();
        for (int i = 1; i < base; i++) {
            String s = all.get(i);
            for (int j = 0; j < FUZZY.length && all.size() < max; j++) {
                addVariant(all, seen, s, FUZZY[j][0], FUZZY[j][1]);
                addVariant(all, seen, s, FUZZY[j][1], FUZZY[j][0]);
            }
            if (all.size() >= max) {
                break;
            }
        }
        return all.subList(1, all.size()).toArray(new String[0]);
    }

    private static final String[] KB_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

    /** 键盘上左右相邻的键，用来模拟「按错键」。 */
    private static String neighboursOf(char c) {
        for (int r = 0; r < KB_ROWS.length; r++) {
            int i = KB_ROWS[r].indexOf(c);
            if (i < 0) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (i > 0) {
                sb.append(KB_ROWS[r].charAt(i - 1));
            }
            if (i + 1 < KB_ROWS[r].length()) {
                sb.append(KB_ROWS[r].charAt(i + 1));
            }
            return sb.toString();
        }
        return "";
    }

    /** 整串是否都是合法音节（用来给纠错结果排序）。 */
    private boolean validPinyin(String s) {
        int pos = 0;
        while (pos < s.length()) {
            int hit = -1;
            int maxLen = Math.min(MAX_SYL_LEN, s.length() - pos);
            for (int l = maxLen; l >= 1; l--) {
                if (syllables.contains(s.substring(pos, pos + l))) {
                    hit = l;
                    break;
                }
            }
            if (hit < 0) {
                return false;
            }
            pos += hit;
        }
        return true;
    }

    /** 拼错候选：相邻颠倒 / 漏字母 / 按错键。 */
    private static String[] typoVariants(String key, int max) {
        List<String> other = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        seen.add(key);
        for (int i = 0; i + 1 < key.length(); i++) {
            String v = key.substring(0, i) + key.charAt(i + 1) + key.charAt(i)
                    + key.substring(i + 2);
            if (seen.add(v)) {
                other.add(v);
            }
        }
        for (int i = 0; i < key.length(); i++) {
            String v = key.substring(0, i) + key.substring(i + 1);
            if (v.length() > 0 && seen.add(v)) {
                other.add(v);
            }
        }
        for (int i = 0; i < key.length() && other.size() < max; i++) {
            String nb = neighboursOf(key.charAt(i));
            for (int j = 0; j < nb.length(); j++) {
                String v = key.substring(0, i) + nb.charAt(j) + key.substring(i + 1);
                if (seen.add(v)) {
                    other.add(v);
                }
            }
        }
        return other.toArray(new String[0]);
    }

    /**
     * 带拼音校正的搜索：先精确查，结果不够再补模糊音，再不够才试拼错纠正。
     * 命中的词条会带 matchedKey，界面据此提示「已纠正」。
     */
    /** 模糊音的代价：纠正来的词要比精确命中明显更常用，才能排到前面。 */
    private static final int FUZZY_PENALTY = 20000;
    /** 拼错纠正的代价更大（更不确定）。 */
    private static final int TYPO_PENALTY = 60000;

    /**
     * 带拼音校正的搜索。
     *
     * 精确命中和模糊音结果放在一起按「词频分 + 惩罚」排序，所以
     * sihou 会先给「时候」(≈shihou) 再给「嗣后」；拼错纠正只在
     * 精确命中为空时才试（nihso → 你好≈nihao）。
     * 命中的词条带 matchedKey，界面据此提示「已纠正」。
     */
    public List<Candidate> searchSmart(String input, int max, boolean fuzzy, boolean typo) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (input == null || input.length() == 0 || lineCount == 0) {
            return out;
        }
        final String key = input.toLowerCase();
        final Map<String, Integer> score = new HashMap<String, Integer>();
        List<Candidate> pool = new ArrayList<Candidate>();

        List<Candidate> exact = search(key, max);
        for (int i = 0; i < exact.size(); i++) {
            poolAdd(pool, score, exact.get(i), key, 0);
        }
        int exactCount = pool.size();

        if (fuzzy) {
            String[] vs = fuzzyVariants(key, 14);
            for (int i = 0; i < vs.length; i++) {
                List<Candidate> r = search(vs[i], 6);
                for (int j = 0; j < r.size(); j++) {
                    poolAdd(pool, score, r.get(j), vs[i], FUZZY_PENALTY);
                }
            }
        }
        if (typo && exactCount == 0) {
            String[] vs = typoVariants(key, 48);
            List<String> ordered = new ArrayList<String>();
            for (int i = 0; i < vs.length; i++) {
                if (validPinyin(vs[i])) {
                    ordered.add(vs[i]);
                }
            }
            for (int i = 0; i < vs.length; i++) {
                if (!validPinyin(vs[i])) {
                    ordered.add(vs[i]);
                }
            }
            for (int i = 0; i < ordered.size(); i++) {
                String v = ordered.get(i);
                List<Candidate> r = search(v, 6);
                for (int j = 0; j < r.size(); j++) {
                    poolAdd(pool, score, r.get(j), v, TYPO_PENALTY);
                }
            }
        }

        Collections.sort(pool, new Comparator<Candidate>() {
            public int compare(Candidate a, Candidate b) {
                Integer sa = score.get(a.simp);
                Integer sb = score.get(b.simp);
                int va = sa == null ? Integer.MAX_VALUE : sa.intValue();
                int vb = sb == null ? Integer.MAX_VALUE : sb.intValue();
                if (va != vb) {
                    return va < vb ? -1 : 1;
                }
                return a.simp.length() - b.simp.length();
            }
        });
        for (int i = 0; i < pool.size() && out.size() < max; i++) {
            out.add(pool.get(i));
        }
        return out;
    }

    private static void poolAdd(List<Candidate> pool, Map<String, Integer> score,
                                Candidate c, String key, int penalty) {
        int s = c.rank + penalty;
        Integer old = score.get(c.simp);
        if (old == null) {
            score.put(c.simp, Integer.valueOf(s));
            pool.add(tag(c, key));
        } else if (s < old.intValue()) {
            score.put(c.simp, Integer.valueOf(s));
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).simp.equals(c.simp)) {
                    pool.set(i, tag(c, key));
                    break;
                }
            }
        }
    }

    private void collect(String variant, Set<String> seen, List<Candidate> out, int max) {
        List<Candidate> r = search(variant, 6);
        for (int i = 0; i < r.size() && out.size() < max; i++) {
            Candidate c = r.get(i);
            if (seen.add(c.simp)) {
                out.add(tag(c, variant));
            }
        }
    }

    private static Candidate tag(Candidate c, String key) {
        return new Candidate(c.simp, c.trad, c.pinyin, c.english, c.englishWord,
                c.raw, c.keyLen, c.rank, key);
    }

    public int size() {
        return lineCount;
    }

    /**
     * 汉字词频表（按 BMP 码位直接索引），用于整句切分打分。
     * 懒加载：第一次切分时构建，字典本身在后台线程加载完会先预热一次。
     */
    private int[] charFreqTable() {
        int[] t = charFreqTable;
        if (t != null) {
            return t;
        }
        synchronized (this) {
            if (charFreqTable == null) {
                t = new int[0x10000];
                for (int i = 0; i < lineCount; i++) {
                    if (keyLen[i] > 6) {
                        continue;   // 单字的拼音最多 6 个字母
                    }
                    int p = off[i] + keyLen[i] + 1;
                    int e = off[i] + len[i];
                    if (p >= e) {
                        continue;
                    }
                    int simpEnd = p;
                    while (simpEnd < e && data[simpEnd] != '\t') {
                        simpEnd++;
                    }
                    if (simpEnd - p != 3) {
                        continue;   // 只认单个汉字（UTF-8 三字节）
                    }
                    char ch = (char) (((data[p] & 0x0f) << 12)
                            | ((data[p + 1] & 0x3f) << 6)
                            | (data[p + 2] & 0x3f));
                    int q = e - 1;
                    while (q > p && data[q] != '\t') {
                        q--;
                    }
                    int freq = 2000000 - parseInt(data, q + 1, e);
                    if (freq < 1) {
                        freq = 1;
                    }
                    if (freq > t[ch]) {
                        t[ch] = freq;
                    }
                }
                charFreqTable = t;
            }
            return charFreqTable;
        }
    }

    private static int parseInt(byte[] d, int s, int e) {
        int v = 0;
        for (int i = s; i < e; i++) {
            byte b = d[i];
            if (b >= '0' && b <= '9') {
                v = v * 10 + (b - '0');
            }
        }
        return v;
    }
}
