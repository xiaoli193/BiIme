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
import java.util.List;

/**
 * 联想索引。
 *
 * assets/assoc.idx 是按「中文」排序的词表，每行：中文 \t 词频分 \t 英文。
 * 上屏「北京」之后，用二分查找取出所有以「北京」开头的词（北京市 / 北京大学 / 北京猿人…）
 * 按词频排序给候选栏，就是联想输入。
 *
 * 和 PinyinDict 一样用「字节数组 + int 偏移」常驻内存，不产生几万个 String 对象。
 */
public final class Assoc {

    private static final String TAG = "BiimeAssoc";
    private static final String ASSET = "assoc.idx";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_SCAN = 400;

    private static volatile Assoc sInstance;

    private final byte[] data;
    private final int[] off;
    private final int[] len;
    private final int count;

    private static final Comparator<Candidate> BY_RANK = new Comparator<Candidate>() {
        public int compare(Candidate a, Candidate b) {
            if (a.rank != b.rank) {
                return a.rank < b.rank ? -1 : 1;
            }
            return a.simp.length() - b.simp.length();
        }
    };

    private Assoc(byte[] data, int[] off, int[] len, int count) {
        this.data = data;
        this.off = off;
        this.len = len;
        this.count = count;
    }

    public static Assoc get(Context context) {
        Assoc a = sInstance;
        if (a == null) {
            synchronized (Assoc.class) {
                a = sInstance;
                if (a == null) {
                    a = load(context.getApplicationContext());
                    sInstance = a;
                }
            }
        }
        return a;
    }

    private static Assoc load(Context context) {
        long t0 = System.currentTimeMillis();
        byte[] data = readAsset(context);
        int lines = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines++;
            }
        }
        int[] off = new int[lines + 1];
        int[] len = new int[lines + 1];
        int n = 0;
        int start = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                if (i > start && n < off.length) {
                    off[n] = start;
                    len[n] = i - start;
                    n++;
                }
                start = i + 1;
            }
        }
        Log.i(TAG, "联想索引 " + n + " 条，耗时 " + (System.currentTimeMillis() - t0) + "ms");
        return new Assoc(data, off, len, n);
    }

    private static byte[] readAsset(Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 21);
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) {
                bos.write(buf, 0, k);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "读取联想索引失败", e);
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

    /** 一行的「中文」字段长度（到第一个 \t 为止）。 */
    private int keyLen(int i) {
        int p = off[i];
        int e = off[i] + len[i];
        while (p < e && data[p] != '\t') {
            p++;
        }
        return p - off[i];
    }

    private int compareKey(int i, byte[] head) {
        int kl = keyLen(i);
        int n = Math.min(kl, head.length);
        for (int j = 0; j < n; j++) {
            int a = data[off[i] + j] & 0xff;
            int b = head[j] & 0xff;
            if (a != b) {
                return a - b;
            }
        }
        return kl - head.length;
    }

    private int lowerBound(byte[] head) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareKey(mid, head) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * 取出所有以 head 开头的词，按词频排序。head 自身会被跳过。
     */
    public List<Candidate> lookup(String head, int max) {
        List<Candidate> out = new ArrayList<Candidate>();
        if (head == null || head.length() == 0 || count == 0) {
            return out;
        }
        byte[] hb = head.getBytes(UTF8);
        List<Candidate> hits = new ArrayList<Candidate>();
        for (int i = lowerBound(hb); i < count && hits.size() < MAX_SCAN; i++) {
            int kl = keyLen(i);
            if (kl < hb.length) {
                break;
            }
            boolean same = true;
            for (int j = 0; j < hb.length; j++) {
                if ((data[off[i] + j] & 0xff) != (hb[j] & 0xff)) {
                    same = false;
                    break;
                }
            }
            if (!same) {
                break;
            }
            String word = new String(data, off[i], kl, UTF8);
            if (word.equals(head)) {
                continue;   // 不联想自己
            }
            String[] f = fields(i);
            int rank = 9999999;
            try {
                rank = Integer.parseInt(f[1]);
            } catch (Exception ignored) {
            }
            hits.add(new Candidate(word, word, "", f[2], f[2], null, 0, rank));
        }
        Collections.sort(hits, BY_RANK);
        for (int i = 0; i < hits.size() && out.size() < max; i++) {
            out.add(hits.get(i));
        }
        return out;
    }

    private String[] fields(int i) {
        String[] f = new String[]{"", "", ""};
        int start = off[i];
        int e = off[i] + len[i];
        int fi = 0;
        for (int q = start; q <= e && fi < 3; q++) {
            if (q == e || data[q] == '\t') {
                f[fi++] = new String(data, start, q - start, UTF8);
                start = q + 1;
            }
        }
        return f;
    }

    public int size() {
        return count;
    }
}
