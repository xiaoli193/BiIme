package com.dsh.biime;

import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 中英对照拼音输入法。
 *
 * 与普通拼音输入法唯一的区别：候选栏中每个中文候选下方同时显示它的英文释义，
 * 长按候选（或在工具条切到「输出英文」）即可直接上屏英文。
 */
public class BiImeService extends InputMethodService {

    private static final int PAGE_LETTERS = 0;
    private static final int PAGE_SYMBOLS = 1;   // 中文标点
    private static final int PAGE_NUM = 2;       // 数字符号
    private static final int PAGE_MORE = 3;      // 更多符号

    private static final String[] ROW1 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private static final String[] ROW2 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
    private static final String[] ROW3 = {"z", "x", "c", "v", "b", "n", "m"};
    private static final String[] SYM1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    // 符号页按 26 键排布（10 / 9 / 7），和字母页同样的键位形状；
    // 扩展包可以用 punct.page1/2/3 覆盖，最多 26 个。
    private static final String[] PUNCT_ZH = {
            "、", "。", "，", "？", "！", "：", "；", "“", "”", "…",
            "‘", "’", "（", "）", "《", "》", "【", "】", "—",
            "·", "～", "〈", "〉", "「", "」", "『"};
    private static final String[] SYM_NUM = {
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "@", "#", "￥", "%", "&", "*", "-", "+", "(",
            ")", "=", "/", "\\", "_", "~", "^"};
    private static final String[] SYM_MORE = {
            "×", "÷", "≈", "±", "°", "§", "¥", "€", "£", "©",
            "®", "™", "№", "‰", "µ", "Ω", "℃", "㎡", "㎏",
            "√", "∞", "π", "∑", "∫", "≠", "≤"};

    /** 长按标点键时，候选栏给出的备选。 */
    private static final String[][] PUNCT_ALTS = {
            {"，", ",", "、", "；"},
            {"。", ".", "…", "！"},
            {"？", "?", "？！"},
            {"！", "!", "！!"},
            {"：", ":", "；"},
            {"；", ";", "，"},
            {"、", "，", "/"},
            {"…", "。", "—"},
            {"“", "”", "「", "『"},
            {"”", "“", "」", "』"},
            {"（", "(", "【", "《"},
            {"）", ")", "】", "》"},
            {"《", "〈", "【"},
            {"》", "〉", "】"},
            {"—", "——", "…"},
            {"·", "・", "."},
    };

    /** 皮肤样式令牌：makeKey 的第三个参数。 */
    private static final int SKIN_NORMAL = Skin.KEY;
    private static final int SKIN_SPECIAL = Skin.KEY_SPECIAL;
    private static final int SKIN_ACCENT = Skin.KEY_ACCENT;

    // 键盘尺寸来自扩展包合并后的 Tuning，所以是实例字段
    private float keySp = 19f;
    private int rowDp = 44;
    private Packs.Tuning tuning;
    private String[] altChoices;     // 非空时候选栏显示标点备选
    private List<Candidate> associations;   // 上屏后的联想候选
    private long lastSpaceAt;
    private boolean lastWasSpace;

    // 当前皮肤（来自「社区扩展包 → 主题包」），每次键盘弹出时重读
    private Packs.Theme theme;
    private Skin skin;
    private int C_PANEL;
    private int C_TEXT;
    private int C_TEXT_DIM;
    private int C_EN;

    private PinyinDict dict;
    private boolean dictLoading;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable repeatRun;

    private LinearLayout candidateRow;
    private LinearLayout keyboardHost;
    private TextView outKey;
    private TextView tradKey;
    private TextView shiftKey;
    private final List<TextView> letterKeys = new ArrayList<TextView>();

    private final StringBuilder composing = new StringBuilder();
    private boolean shiftOn;
    private boolean capsOn;
    private boolean outputEnglish;    // true = 点候选直接上屏英文
    private boolean useTraditional;   // true = 上屏繁体
    private int page = PAGE_LETTERS;

    // ------------------------------------------------------------- 生命周期

    /** 读当前主题。应用里换了主题后，键盘下次弹出就会用新配色。 */
    private void loadTheme() {
        theme = Packs.activeTheme(this);
        tuning = Packs.tuning(this);
        skin = new Skin(this, theme, tuning.keyRadius);
        keySp = tuning.keyFont;
        rowDp = tuning.rowHeight;
        C_PANEL = theme.panel;
        C_TEXT = theme.text;
        C_TEXT_DIM = theme.textDim;
        C_EN = theme.english;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadTheme();
        loadDictionary();
    }

    private void loadDictionary() {
        if (dict != null || dictLoading) {
            return;
        }
        dictLoading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final PinyinDict d = PinyinDict.get(BiImeService.this);
                    handler.post(new Runnable() {
                        public void run() {
                            dict = d;
                            dictLoading = false;
                            if (composing.length() > 0) {
                                refreshCandidates();
                            }
                        }
                    });
                } catch (Throwable e) {
                    CrashLog.save(BiImeService.this, "ime-dict-load", e);
                }
            }
        }, "pinyin-dict-loader").start();
    }

    @Override
    public View onCreateInputView() {
        CrashLog.install(getApplicationContext());
        try {
            Packs.markImeActive(this);
            loadTheme();
            return buildRoot();
        } catch (Throwable e) {
            // 键盘构建失败也不能让输入法进程挂掉，给一个能看清错误的最小键盘
            CrashLog.save(this, "ime-oncreateinputview", e);
            return keyboardErrorView(e);
        }
    }

    private View keyboardErrorView(Throwable e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF201014);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView t = new TextView(this);
        t.setText("键盘构建失败，堆栈已写入 Download/biime-crash-*.txt");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(0xFFFFD5D5);
        box.addView(t);
        TextView s = new TextView(this);
        s.setText(CrashLog.stack(e));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        s.setTextColor(0xFFDDBBBB);
        s.setMaxLines(20);
        box.addView(s);
        return box;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        try {
            Packs.markImeActive(this);   // 首页据此判断「最近在用本输入法」
            loadTheme();      // 应用里刚换过主题的话，这次弹出就用新的
            composing.setLength(0);
            shiftOn = false;
            capsOn = false;
            if (tuning != null) {
                outputEnglish = tuning.englishOutput;
                useTraditional = tuning.traditional;
            }
            buildPage(PAGE_LETTERS);
            updateToolbar();
            refreshCandidates();
            loadDictionary();
        } catch (Throwable e) {
            CrashLog.save(this, "ime-onstartinputview", e);
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        composing.setLength(0);
        cancelRepeat();
    }

    @Override
    public void onDestroy() {
        cancelRepeat();
        super.onDestroy();
    }

    /** 输入法键盘不用全屏编辑模式，否则横屏时候选栏会被顶掉。 */
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        // 应用把组合区清掉了（光标移走 / 上屏），本地状态跟着清
        if (composing.length() > 0 && candidatesEnd <= candidatesStart) {
            composing.setLength(0);
            refreshCandidates();
        }
    }

    // --------------------------------------------------------------- 视图

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_PANEL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildCandidateBar());

        keyboardHost = new LinearLayout(this);
        keyboardHost.setOrientation(LinearLayout.VERTICAL);
        keyboardHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(keyboardHost);

        buildPage(PAGE_LETTERS);
        return root;
    }

    private TextView makeToolKey() {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTextColor(C_TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(skin.key(SKIN_SPECIAL));
        tv.setPadding(dp(3), 0, dp(3), 0);
        tv.setMinWidth(dp(29));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(1), 0, dp(1), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildCandidateBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(theme.bar);
        bar.setPadding(dp(2), dp(3), dp(2), dp(3));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        candidateRow = new LinearLayout(this);
        candidateRow.setOrientation(LinearLayout.HORIZONTAL);
        candidateRow.setGravity(Gravity.CENTER_VERTICAL);
        candidateRow.setPadding(dp(2), 0, dp(2), 0);
        scroll.addView(candidateRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(scroll);

        // 工具条并进这一行：中/EN、简/繁、收起
        outKey = makeToolKey();
        outKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                outputEnglish = !outputEnglish;
                updateToolbar();
                refreshCandidates();
            }
        });
        bar.addView(outKey);

        tradKey = makeToolKey();
        tradKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                useTraditional = !useTraditional;
                updateToolbar();
                refreshCandidates();
            }
        });
        bar.addView(tradKey);

        TextView hide = makeToolKey();
        hide.setText("\u2304");
        hide.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                requestHideSelf(0);
            }
        });
        bar.addView(hide);
        return bar;
    }

    private void updateToolbar() {
        if (outKey == null) {
            return;
        }
        outKey.setText(outputEnglish ? "EN" : "中");
        outKey.setBackground(skin.key(outputEnglish ? SKIN_ACCENT : SKIN_SPECIAL));
        tradKey.setText(useTraditional ? "繁" : "简");
    }

    // ------------------------------------------------------------- 键盘构建

    private LinearLayout makeRow(int heightDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return row;
    }

    private TextView makeKey(String label, float weight, int keyStyle, float sp) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(C_TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setBackground(skin.key(keyStyle));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        tv.setLayoutParams(lp);
        return tv;
    }

    private View spacerKey(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        return v;
    }

    private TextView letterKey(String lower) {
        final TextView tv = makeKey(lower, 1f, SKIN_NORMAL, keySp);
        tv.setTag(lower);
        letterKeys.add(tv);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String base = (String) v.getTag();
                boolean upper = capsOn || shiftOn;
                if (shiftOn && !capsOn) {
                    shiftOn = false;
                    updateShiftLabels();
                }
                onLetter(upper ? base.toUpperCase() : base);
            }
        });
        return tv;
    }

    private TextView holdToDeleteKey(float weight) {
        final TextView tv = makeKey("⌫", weight, SKIN_SPECIAL, 18f);
        tv.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        onBackspace();
                        scheduleRepeat();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        cancelRepeat();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return tv;
    }

    private void scheduleRepeat() {
        cancelRepeat();
        repeatRun = new Runnable() {
            public void run() {
                onBackspace();
                handler.postDelayed(this, 45);
            }
        };
        handler.postDelayed(repeatRun, 380);
    }

    private void cancelRepeat() {
        if (repeatRun != null) {
            handler.removeCallbacks(repeatRun);
            repeatRun = null;
        }
    }

    private void updateShiftLabels() {
        boolean upper = capsOn || shiftOn;
        for (int i = 0; i < letterKeys.size(); i++) {
            TextView tv = letterKeys.get(i);
            String base = (String) tv.getTag();
            tv.setText(upper ? base.toUpperCase() : base);
        }
        if (shiftKey != null) {
            shiftKey.setBackground(skin.key(upper ? SKIN_ACCENT : SKIN_SPECIAL));
        }
    }

    private void buildPage(int p) {
        if (keyboardHost == null) {
            return;
        }
        page = p;
        keyboardHost.removeAllViews();
        letterKeys.clear();
        shiftKey = null;

        if (p == PAGE_LETTERS) {
            // 扩展包给的快捷短语行
            if (tuning != null && tuning.keyRow != null && tuning.keyRow.size() > 0) {
                LinearLayout kr = makeRow(rowDp);
                for (int i = 0; i < tuning.keyRow.size(); i++) {
                    kr.addView(quickKey(tuning.keyRow.get(i)));
                }
                keyboardHost.addView(kr);
            }
            if (tuning != null && tuning.numberRow) {
                LinearLayout r0 = makeRow(rowDp);
                for (int i = 0; i < SYM1.length; i++) {
                    r0.addView(digitKey(SYM1[i]));
                }
                keyboardHost.addView(r0);
            }
            LinearLayout r1 = makeRow(rowDp);
            for (int i = 0; i < ROW1.length; i++) {
                r1.addView(letterKey(ROW1[i]));
            }
            keyboardHost.addView(r1);

            LinearLayout r2 = makeRow(rowDp);
            r2.addView(spacerKey(0.5f));
            for (int i = 0; i < ROW2.length; i++) {
                r2.addView(letterKey(ROW2[i]));
            }
            r2.addView(spacerKey(0.5f));
            keyboardHost.addView(r2);

            LinearLayout r3 = makeRow(rowDp);
            shiftKey = makeKey("⇧", 1.5f, SKIN_SPECIAL, 17f);
            shiftKey.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (capsOn) {
                        capsOn = false;
                        shiftOn = false;
                    } else if (shiftOn) {
                        capsOn = true;
                        shiftOn = false;
                    } else {
                        shiftOn = true;
                    }
                    updateShiftLabels();
                }
            });
            r3.addView(shiftKey);
            for (int i = 0; i < ROW3.length; i++) {
                r3.addView(letterKey(ROW3[i]));
            }
            r3.addView(holdToDeleteKey(1.5f));
            keyboardHost.addView(r3);

            keyboardHost.addView(bottomRow(pageKeyLabelFor(PAGE_LETTERS)));
            updateShiftLabels();
            return;
        }

        Packs.Punct pn = currentPunct();
        List<String> custom = null;
        String[] fallback;
        if (p == PAGE_SYMBOLS) {
            custom = pn.page1;
            fallback = PUNCT_ZH;
        } else if (p == PAGE_NUM) {
            custom = pn.page2;
            fallback = SYM_NUM;
        } else {
            custom = pn.page3;
            fallback = SYM_MORE;
        }
        addSymbolRows(custom, fallback);
        keyboardHost.addView(bottomRow(pageKeyLabelFor(p)));
    }

    private String pageKeyLabelFor(int p) {
        if (p == PAGE_SYMBOLS) {
            return "#+=";
        }
        if (p == PAGE_NUM) {
            return "更多";
        }
        if (p == PAGE_MORE) {
            return "ABC";
        }
        return "符";
    }

    private int nextPage(int p) {
        if (p == PAGE_LETTERS) {
            return PAGE_SYMBOLS;
        }
        if (p == PAGE_SYMBOLS) {
            return PAGE_NUM;
        }
        if (p == PAGE_NUM) {
            return PAGE_MORE;
        }
        return PAGE_LETTERS;
    }

    private Packs.Punct currentPunct() {
        return (tuning != null && tuning.punct != null) ? tuning.punct : Packs.DEFAULT_PUNCT;
    }

    /**
     * 渲染一页符号，排布和 26 键字母页完全一致：
     * 第一行 10 格，第二行 0.5 + 9 + 0.5，第三行 1.5 + 7 + 删除。
     * 符号不够 26 个就用等宽空位补齐，所以换页时键宽和键盘高度都不会跳。
     */
    private void addSymbolRows(List<String> custom, String[] fallback) {
        String[] keys = (custom != null && custom.size() > 0)
                ? custom.toArray(new String[0]) : fallback;
        int i = 0;

        LinearLayout r1 = makeRow(rowDp);
        for (int n = 0; n < 10; n++) {
            if (i < keys.length) {
                r1.addView(symbolKey(keys[i++]));
            } else {
                r1.addView(spacerKey(1f));
            }
        }
        keyboardHost.addView(r1);

        LinearLayout r2 = makeRow(rowDp);
        r2.addView(spacerKey(0.5f));
        for (int n = 0; n < 9; n++) {
            if (i < keys.length) {
                r2.addView(symbolKey(keys[i++]));
            } else {
                r2.addView(spacerKey(1f));
            }
        }
        r2.addView(spacerKey(0.5f));
        keyboardHost.addView(r2);

        LinearLayout r3 = makeRow(rowDp);
        r3.addView(spacerKey(1.5f));
        for (int n = 0; n < 7; n++) {
            if (i < keys.length) {
                r3.addView(symbolKey(keys[i++]));
            } else {
                r3.addView(spacerKey(1f));
            }
        }
        r3.addView(holdToDeleteKey(1.5f));
        keyboardHost.addView(r3);

        // 扩展包给了超过 26 个符号时，多出来的另起一行
        while (i < keys.length) {
            LinearLayout r = makeRow(rowDp);
            int n = 0;
            while (i < keys.length && n < 10) {
                r.addView(symbolKey(keys[i++]));
                n++;
            }
            if (i >= keys.length) {
                r.addView(holdToDeleteKey(1f));
            }
            keyboardHost.addView(r);
        }
    }

    /** 扩展包自定义的快捷短语键。 */
    private TextView quickKey(final String text) {
        TextView tv = makeKey(text, 1f, SKIN_NORMAL, 13f);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (composing.length() > 0) {
                    commitTopCandidate();
                }
                commitText(text);
            }
        });
        return tv;
    }

    /** 数字行的按键：直接插入数字，不动候选。 */
    private TextView digitKey(final String text) {
        TextView tv = makeKey(text, 1f, SKIN_NORMAL, keySp - 3f);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                commitText(text);
            }
        });
        return tv;
    }

    private TextView symbolKey(final String text) {
        // 长符号（颜文字之类）自动缩小字号，免得挤出格子
        float sp = text.length() > 6 ? 9f : (text.length() > 4 ? 11f : (text.length() > 2 ? 14f : 17f));
        TextView tv = makeKey(text, 1f, SKIN_NORMAL, sp);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (composing.length() > 0) {
                    commitTopCandidate();
                }
                commitText(text);
            }
        });
        attachAlternatives(tv, text);
        return tv;
    }

    /** 长按标点键 → 候选栏给出该键的其它写法（半角 / 相关符号）。 */
    private void attachAlternatives(TextView tv, final String ch) {
        final String[] alts = alternativesFor(ch);
        if (alts == null || alts.length < 2) {
            return;
        }
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                altChoices = alts;
                refreshCandidates();
                return true;
            }
        });
    }

    private static String[] alternativesFor(String ch) {
        for (int i = 0; i < PUNCT_ALTS.length; i++) {
            if (PUNCT_ALTS[i][0].equals(ch)) {
                return PUNCT_ALTS[i];
            }
        }
        return null;
    }

    private LinearLayout bottomRow(String pageKeyLabel) {
        LinearLayout r = makeRow(rowDp);

        TextView pageKey = makeKey(pageKeyLabel, 1.4f, SKIN_SPECIAL, 15f);
        pageKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                buildPage(nextPage(page));
            }
        });
        r.addView(pageKey);

        Packs.Punct pn = currentPunct();
        r.addView(punctKey(pn.comma == null ? "，" : pn.comma));
        r.addView(spaceKey());
        r.addView(punctKey(pn.period == null ? "。" : pn.period));
        r.addView(enterKey());
        return r;
    }

    private TextView punctKey(final String text) {
        TextView tv = makeKey(text, 1f, SKIN_SPECIAL, 18f);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (composing.length() > 0) {
                    commitTopCandidate();
                }
                commitText(text);
            }
        });
        attachAlternatives(tv, text);
        return tv;
    }

    private TextView spaceKey() {
        TextView tv = makeKey("空格", 4.5f, SKIN_NORMAL, 14f);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (tuning != null && tuning.doubleSpacePeriod && lastWasSpace
                        && composing.length() == 0 && now - lastSpaceAt < 450) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.deleteSurroundingText(1, 0);
                        ic.commitText(currentPunct().period == null ? "。" : currentPunct().period, 1);
                    }
                    lastWasSpace = false;
                    lastSpaceAt = 0;
                    return;
                }
                boolean spaceCommit = tuning == null || tuning.spaceCommit;
                if (composing.length() > 0 && spaceCommit) {
                    commitTopCandidate();
                } else if (composing.length() > 0) {
                    // 「空格只打空格」：把原样字母 + 空格打出去，不上屏候选
                    String raw = composing.toString();
                    composing.setLength(0);
                    commitText(raw + " ");
                } else {
                    commitText(" ");
                }
            }
        });
        return tv;
    }

    private TextView enterKey() {
        TextView tv = makeKey("换行", 1.4f, SKIN_ACCENT, 14f);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (composing.length() > 0) {
                    commitTopCandidate();
                    return;
                }
                sendEnter();
            }
        });
        return tv;
    }

    private void sendEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE
                : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    // ------------------------------------------------------------- 候选栏

    private void showIdleHint() {
        if (candidateRow == null) {
            return;
        }
        candidateRow.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText(dictLoading || dict == null
                ? "正在加载中英词典…"
                : "输入拼音，候选下面的小字就是英文");
        tv.setTextColor(C_TEXT_DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(10), 0, dp(10), 0);
        candidateRow.addView(tv);
    }

    private void refreshCandidates() {
        if (candidateRow == null) {
            return;
        }
        candidateRow.removeAllViews();
        if (altChoices != null) {
            for (int i = 0; i < altChoices.length; i++) {
                candidateRow.addView(altChip(altChoices[i]));
            }
            return;
        }
        final String input = composing.toString();
        if (input.length() == 0) {
            if (associations != null && associations.size() > 0) {
                // 上屏之后的联想词
                for (int i = 0; i < associations.size(); i++) {
                    candidateRow.addView(makeChip(associations.get(i), i));
                }
                return;
            }
            showIdleHint();
            return;
        }

        // 候选拼装（短语包 → 词典 → 整句兜底切分）和应用内试打演示共用一份逻辑
        boolean loading = dict == null;
        List<Candidate> list = Candidates.build(this, dict, input, useTraditional);

        // 拼音被校正过的话，先给个小提示（≈ 后面是实际命中的拼音）
        if (list.size() > 0) {
            String mk = list.get(0).matchedKey;
            if (mk != null && !mk.equals(input.toLowerCase())) {
                candidateRow.addView(hintChip("≈" + mk));
            }
        }

        for (int i = 0; i < list.size(); i++) {
            candidateRow.addView(makeChip(list.get(i), i));
        }

        if (loading) {
            TextView tv = new TextView(this);
            tv.setText("词典加载中…");
            tv.setTextColor(C_TEXT_DIM);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setPadding(dp(10), 0, dp(10), 0);
            candidateRow.addView(tv);
        }

        // 原样输入候选（打英文 / 拼音查不到时用），永远放在最后
        candidateRow.addView(makeChip(Candidate.rawInput(input), -1));
    }

    /** 标点备选 chip。 */
    /** 候选栏里的小提示（比如「≈shi」表示按 shi 纠正过）。 */
    private View hintChip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTextColor(C_TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), 0, dp(8), 0);
        return t;
    }

    /** 联想：把这次上屏的中文尾巴拿去查「以它开头的词」。 */
    private void updateAssociations(String committed) {
        String head = trailingChinese(committed);
        associations = (head == null) ? null : Candidates.associate(this, head, 12);
        if (associations != null && associations.isEmpty()) {
            associations = null;
        }
    }

    private static String trailingChinese(String s) {
        if (s == null) {
            return null;
        }
        int end = s.length();
        int start = end;
        while (start > 0 && isCjk(s.charAt(start - 1))) {
            start--;
        }
        return (start == end) ? null : s.substring(start, end);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4e00 && c <= 0x9fff;
    }

    private View altChip(final String ch) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(skin.card());
        box.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(8), dp(4), dp(8));
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(ch);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        t.setTextColor(C_TEXT);
        box.addView(t);

        TextView hint = new TextView(this);
        hint.setText("标点备选");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        hint.setTextColor(C_TEXT_DIM);
        box.addView(hint);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                commitText(ch);
            }
        });
        return box;
    }

    private View makeChip(final Candidate c, int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(skin.card());
        box.setPadding(dp(9), dp(2), dp(9), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(5), dp(2), dp(5));
        box.setLayoutParams(lp);

        TextView zh = new TextView(this);
        zh.setSingleLine(true);
        zh.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.isRaw() ? 13f : 17f);
        zh.setTextColor(c.isRaw() ? C_TEXT_DIM : C_TEXT);
        zh.setText(c.isRaw() ? c.raw : c.chinese(useTraditional));
        if (index == 0 && !c.isRaw()) {
            zh.setTypeface(Typeface.DEFAULT_BOLD);
        }

        TextView en = new TextView(this);
        en.setSingleLine(true);
        en.setEllipsize(TextUtils.TruncateAt.END);
        en.setMaxWidth(dp(118));
        en.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        en.setTextColor(c.isRaw() ? 0xFF7A7A88 : C_EN);
        if (c.isRaw()) {
            en.setText("abc");
        } else if (c.english.length() == 0) {
            en.setText("—");
        } else {
            en.setText(outputEnglish ? (c.englishWord.length() > 0 ? c.englishWord : c.english)
                    : c.english);
        }

        box.addView(zh);
        box.addView(en);
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                commitCandidate(c);
            }
        });
        if (!c.isRaw()) {
            box.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    commitText(c.englishForOutput(useTraditional));
                    return true;
                }
            });
        }
        return box;
    }

    // ------------------------------------------------------------- 输入逻辑

    private void onLetter(String s) {
        altChoices = null;
        associations = null;
        composing.append(s);
        updateComposingText();
        refreshCandidates();
    }

    private void onBackspace() {
        if (altChoices != null) {
            altChoices = null;
            refreshCandidates();
            return;
        }
        if (associations != null) {
            associations = null;
            refreshCandidates();
            return;
        }
        if (composing.length() > 0) {
            composing.deleteCharAt(composing.length() - 1);
            updateComposingText();
            refreshCandidates();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void commitCandidate(Candidate c) {
        if (c.isRaw()) {
            commitText(c.raw);
            return;
        }
        if (outputEnglish) {
            commitText(c.englishForOutput(useTraditional));
        } else {
            commitText(c.chinese(useTraditional));
        }
    }

    private void commitTopCandidate() {
        String input = composing.toString();
        if (input.length() == 0) {
            return;
        }
        if (dict != null) {
            List<Candidate> list = dict.search(input, 1);
            if (list.size() > 0 && !outputEnglish) {
                commitText(list.get(0).chinese(useTraditional));
                return;
            }
            if (list.size() > 0) {
                commitText(list.get(0).englishForOutput(useTraditional));
                return;
            }
            String seg = dict.segment(input.toLowerCase(), useTraditional);
            if (seg != null && seg.length() > 0) {
                commitText(seg);
                return;
            }
        }
        commitText(input);
    }

    private void commitText(String text) {
        String out = applyPolicy(text);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(out, 1);
        }
        composing.setLength(0);
        altChoices = null;
        lastWasSpace = " ".equals(out);
        lastSpaceAt = lastWasSpace ? System.currentTimeMillis() : 0;
        updateAssociations(out);
        refreshCandidates();
    }

    /** 上屏前统一处理：中文模式自动全角标点 + 扩展包要求的文本转换。 */
    private String applyPolicy(String text) {
        if (text == null || tuning == null) {
            return text;
        }
        String s = text;
        if (tuning.autoPunct && !outputEnglish && s.length() == 1) {
            s = toFullPunct(s);
        }
        String t = tuning.textTransform;
        if (t == null || t.length() == 0 || "none".equals(t)) {
            return s;
        }
        if ("fullwidth".equals(t)) {
            return toFullWidth(s);
        }
        if ("halfwidth".equals(t)) {
            return toHalfWidth(s);
        }
        if ("upper".equals(t)) {
            return s.toUpperCase();
        }
        if ("lower".equals(t)) {
            return s.toLowerCase();
        }
        return s;
    }

    /** 半角标点转全角；转换目标取自扩展包的标点集，所以「半角标点」包不会被转回去。 */
    private String toFullPunct(String s) {
        Packs.Punct pn = currentPunct();
        char c = s.charAt(0);
        if (c == '.') return pn.period == null ? "。" : pn.period;
        if (c == ',') return pn.comma == null ? "，" : pn.comma;
        if (c == '?') return pn.question == null ? "？" : pn.question;
        if (c == '!') return pn.exclaim == null ? "！" : pn.exclaim;
        if (c == ':') return "：";
        if (c == ';') return "；";
        if (c == '(') return "（";
        if (c == ')') return "）";
        return s;
    }

    private static String toFullWidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                sb.append('\u3000');
            } else if (c >= 0x21 && c <= 0x7e) {
                sb.append((char) (c + 0xfee0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= 0xff01 && c <= 0xff5e) {
                sb.append((char) (c - 0xfee0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void updateComposingText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        if (composing.length() == 0) {
            ic.finishComposingText();
        } else {
            ic.setComposingText(composing.toString(), 1);
        }
    }

    // --------------------------------------------------------- 物理键盘支持

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            onBackspace();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && composing.length() > 0) {
            composing.setLength(0);
            updateComposingText();
            refreshCandidates();
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            char c = (char) ('a' + (keyCode - KeyEvent.KEYCODE_A));
            int meta = event.getMetaState();
            boolean upper = (meta & KeyEvent.META_SHIFT_ON) != 0
                    || (meta & KeyEvent.META_CAPS_LOCK_ON) != 0;
            onLetter(String.valueOf(upper ? Character.toUpperCase(c) : c));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
