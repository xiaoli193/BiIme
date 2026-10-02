package com.dsh.biime;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选列表拼装：已启用短语包 → 词典查找 → 整句兜底切分。
 * 键盘（BiImeService）和应用内试打演示（MainActivity）共用这一份，
 * 保证「应用里试出来的效果」和「真键盘打出来的效果」一致。
 */
public final class Candidates {

    public static final int MAX = 40;

    public static List<Candidate> build(Context ctx, PinyinDict dict, String input, boolean traditional) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (input == null || input.length() == 0) {
            return out;
        }
        String key = input.toLowerCase();

        // 1) 用户自己启用的短语包排在最前面
        List<Packs.Entry> phrases = Packs.enabledEntries(ctx);
        for (int i = 0; i < phrases.size(); i++) {
            Packs.Entry e = phrases.get(i);
            if (e.key.startsWith(key)) {
                out.add(new Candidate(e.chinese, e.chinese, e.key, e.english, e.english,
                        null, e.key.length(), -1));
            }
        }

        if (dict == null) {
            return out;
        }

        // 2) 词典（带模糊音 / 拼错纠正）
        Packs.Tuning tn = Packs.tuning(ctx);
        out.addAll(dict.searchSmart(key, MAX, tn.fuzzyPinyin, tn.typoCorrect));

        // 3) 没有整词精确匹配时，补一条整句切分（woaini → 我爱你）
        boolean hasExact = false;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).keyLen == input.length()) {
                hasExact = true;
                break;
            }
        }
        if (!hasExact) {
            Candidate seg = dict.segmentCandidate(key, traditional);
            if (seg != null && seg.chinese(traditional).length() > 0) {
                boolean dup = false;
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).chinese(traditional).equals(seg.chinese(traditional))) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(0, seg);
                }
            }
        }
        return out;
    }

    /**
     * 联想：上屏某个词之后，给出「以它开头的常用词」。
     * 例如上屏「北京」→ 北京市 / 北京大学 / 北京猿人…
     */
    public static List<Candidate> associate(Context ctx, String head, int max) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (head == null || head.length() == 0 || head.length() > 4) {
            return out;
        }
        if (!Packs.tuning(ctx).associate) {
            return out;
        }
        return Assoc.get(ctx).lookup(head, max);
    }
}
