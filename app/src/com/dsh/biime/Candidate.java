package com.dsh.biime;

/** 一条候选：中文词 + 对应英文释义。 */
public final class Candidate {

    public final String simp;        // 简体
    public final String trad;        // 繁体
    public final String pinyin;      // 带声调拼音，如 "ni3 hao3"
    public final String english;     // 展示用英文释义（可能多个义项）
    public final String englishWord; // 上屏用英文（第一义项）
    public final String raw;         // 非 null 表示“原样上屏”候选
    public final int keyLen;         // 该候选拼音键的长度；用于区分精确/前缀匹配
    public final int rank;           // 词频排名分，越小越常用
    /** 实际命中的拼音键；拼音校正后可能与输入不同（null = 未标注）。 */
    public final String matchedKey;

    public Candidate(String simp, String trad, String pinyin, String english,
                     String englishWord, String raw, int keyLen, int rank) {
        this(simp, trad, pinyin, english, englishWord, raw, keyLen, rank, null);
    }

    public Candidate(String simp, String trad, String pinyin, String english,
                     String englishWord, String raw, int keyLen, int rank, String matchedKey) {
        this.matchedKey = matchedKey;
        this.simp = simp;
        this.trad = trad;
        this.pinyin = pinyin;
        this.english = english;
        this.englishWord = englishWord;
        this.raw = raw;
        this.keyLen = keyLen;
        this.rank = rank;
    }

    public static Candidate rawInput(String text) {
        return new Candidate(text, text, "", "", text, text, text.length(), Integer.MAX_VALUE);
    }

    /** 词典里没有整词时，按音节逐字拼出来的候选（如 woaini → 我爱你）。 */
    public static Candidate segmented(String input, String text) {
        return new Candidate(text, text, input, "逐字拼合（词典无此词）", "", null,
                input.length(), Integer.MAX_VALUE - 1);
    }

    public boolean isRaw() {
        return raw != null;
    }

    /** 按简/繁模式取中文上屏文本。 */
    public String chinese(boolean traditional) {
        if (isRaw()) {
            return raw;
        }
        String s = traditional ? trad : simp;
        return (s == null || s.length() == 0) ? simp : s;
    }

    /** 英文上屏文本；没有释义时退回中文，保证不会什么都不上屏。 */
    public String englishForOutput(boolean traditional) {
        if (isRaw()) {
            return raw;
        }
        if (englishWord != null && englishWord.length() > 0) {
            return englishWord;
        }
        return chinese(traditional);
    }
}
