package com.dsh.biime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 应用首页：状态 → 使用引导 → 应用内试打演示 → 社区扩展包。
 *
 * 试打演示用的是应用自带的小键盘，和系统当前用的是哪个输入法无关，
 * 候选拼装走的也是 Candidates / PinyinDict 这一份真逻辑，所以「演示里看到什么，
 * 真键盘里就是什么」。
 */
public class MainActivity extends Activity {

    private static final int REQ_IMPORT = 1001;

    private static final String[] PAD1 = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private static final String[] PAD2 = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
    private static final String[] PAD3 = {"z", "x", "c", "v", "b", "n", "m"};

    private Packs.Theme theme;
    private Skin skin;

    private PinyinDict dict;
    private boolean dictLoading;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private LinearLayout packList;
    private LinearLayout demoBar;
    private TextView demoPinyinView;
    private TextView demoResultView;

    private final StringBuilder demoPinyin = new StringBuilder();
    private final StringBuilder demoResult = new StringBuilder();
    private List<Candidate> demoAssoc;   // 演示区的联想候选
    private boolean packsExpanded;       // 扩展包那一块是否展开
    private View packBody;               // 展开后才显示的内容
    private TextView packArrow;         // 标题上的 ▸ / ▾
    private TextView packSummaryView;   // 标题下的统计

    private String category = Packs.CAT_ALL;

    // ------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(getApplicationContext());
        try {
            theme = Packs.activeTheme(this);
            skin = new Skin(this, theme, Packs.tuning(this).keyRadius);
            packsExpanded = Packs.uiFlag(this, "packs_expanded", false);
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.panel));
            setContentView(buildRoot());
        } catch (Throwable e) {
            // 首页任何一环出问题都不该让应用闪退：直接把堆栈显示出来
            CrashLog.save(this, "home-oncreate", e);
            setContentView(errorView("首页构建失败", e));
        }
        loadDictionary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            updateStatus();
            renderPacks();
        } catch (Throwable e) {
            CrashLog.save(this, "home-onresume", e);
        }
    }

    /** 出错时的兜底页面：不依赖主题和扩展包，只把错误摊开给用户看。 */
    private View errorView(String title, Throwable e) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(24), dp(18), dp(24));

        TextView t = new TextView(this);
        t.setText(title + "\n\n已经写入 Download/biime-crash-*.txt，把这段内容截图或把文件发出来即可定位。");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTextColor(0xFF222222);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText(CrashLog.stack(e));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        s.setTextIsSelectable(true);
        s.setTextColor(0xFF444444);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        s.setLayoutParams(lp);
        box.addView(s);

        scroll.setBackgroundColor(0xFFF5F5F7);
        scroll.addView(box);
        return scroll;
    }

    private void loadDictionary() {
        if (dict != null || dictLoading) {
            return;
        }
        dictLoading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final PinyinDict d = PinyinDict.get(MainActivity.this);
                    handler.post(new Runnable() {
                        public void run() {
                            dict = d;
                            dictLoading = false;
                            refreshDemo();
                        }
                    });
                } catch (final Throwable e) {
                    // 后台线程抛异常会直接带走整个进程，这里必须兜住
                    CrashLog.save(MainActivity.this, "dict-load", e);
                    handler.post(new Runnable() {
                        public void run() {
                            dictLoading = false;
                            refreshDemo();
                        }
                    });
                }
            }
        }, "biime-dict-loader").start();
    }

    // --------------------------------------------------------------- 小工具

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(3), 1f);
        return tv;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(skin.card());
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) l.getLayoutParams();
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 首页

    private View buildRoot() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.panel);
        scroll.setFillViewport(true);

        LinearLayout root = column();
        root.setPadding(dp(16), dp(22), dp(16), dp(30));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(headerView());
        root.addView(statusCard());
        root.addView(sectionTitle("怎么用", "三步就能开始打字"));
        root.addView(guideCard());
        root.addView(demoCard());
        root.addView(packHeader());
        LinearLayout body = column();
        body.addView(importRow());
        body.addView(categoryRow());
        packList = column();
        body.addView(packList);
        body.setVisibility(packsExpanded ? View.VISIBLE : View.GONE);
        packBody = body;
        root.addView(body);
        root.addView(footerView());

        renderPacks();
        updateStatus();
        refreshDemo();
        return scroll;
    }

    private View headerView() {
        LinearLayout box = column();
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.bottomMargin = dp(16);
        box.setLayoutParams(lp);

        TextView title = label("中英对照拼音输入法", 23f, theme.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = label("打拼音的时候，候选栏里每个中文候选下面都会写出它对应的英文。",
                13f, theme.textDim);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        sub.setLayoutParams(slp);
        box.addView(sub);
        return box;
    }

    private View statusCard() {
        LinearLayout box = card();
        box.addView(label("当前状态", 11.5f, theme.textDim));
        statusText = label("", 14.5f, theme.text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        statusText.setLayoutParams(lp);
        box.addView(statusText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        row.setLayoutParams(rlp);
        row.addView(actionButton("去系统设置启用", true, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                } catch (Exception e) {
                    toast("打不开设置，请手动进：设置 → 系统 → 语言和输入法");
                }
            }
        }));
        row.addView(actionButton("切换输入法", false, new View.OnClickListener() {
            public void onClick(View v) {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showInputMethodPicker();
                }
            }
        }));
        box.addView(row);
        return box;
    }

    private TextView actionButton(String text, boolean primary, View.OnClickListener l) {
        TextView tv = label(text, 13f, primary ? 0xFFFFFFFF : theme.text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackground(skin.outlined(primary ? theme.accent : theme.key,
                primary ? 0 : theme.chipBorder, 8));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 扩展包那一块的可折叠标题：一行标题 + 一行统计，点一下展开/收起。 */
    private View packHeader() {
        LinearLayout box = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label("扩展包", 16f, theme.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(title);

        packArrow = label(packsExpanded ? "\u25be" : "\u25b8", 15f, theme.textDim);
        packArrow.setGravity(Gravity.CENTER);
        packArrow.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(packArrow);

        box.addView(row);

        packSummaryView = label(packSummary(), 11.5f, theme.textDim);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        packSummaryView.setLayoutParams(slp);
        box.addView(packSummaryView);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setPacksExpanded(!packsExpanded);
            }
        });
        return box;
    }

    private String packSummary() {
        List<Packs.Pack> all = Packs.catalog(this);
        int enabled = 0;
        for (int i = 0; i < all.size(); i++) {
            Packs.Pack p = all.get(i);
            if (p.is(Packs.CAT_THEME) ? p.id.equals(Packs.activeThemeId(this))
                    : Packs.isEnabled(this, p.id)) {
                enabled++;
            }
        }
        return all.size() + " 个扩展包 · 已启用 " + enabled + " 个"
                + (packsExpanded ? " · 点标题收起"
                : " · 配色 / 键盘 / 标点 / 词条 / 功能，点标题展开");
    }

    /** 原地切换显隐，不重建界面——否则会把页面滚回顶部。 */
    private void setPacksExpanded(boolean expanded) {
        packsExpanded = expanded;
        Packs.setUiFlag(this, "packs_expanded", expanded);
        if (packBody != null) {
            packBody.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (packArrow != null) {
            packArrow.setText(expanded ? "\u25be" : "\u25b8");
        }
        if (packSummaryView != null) {
            packSummaryView.setText(packSummary());
        }
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout box = column();
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(10);
        box.setLayoutParams(lp);

        TextView t = label(title, 16f, theme.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView s = label(subtitle, 11.5f, theme.textDim);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        s.setLayoutParams(slp);
        box.addView(s);
        return box;
    }

    private View guideCard() {
        LinearLayout box = card();
        box.addView(stepRow("1", "启用输入法",
                "点上面的「去系统设置启用」，在输入法列表里勾选「中英对照拼音输入法」。"));
        box.addView(stepRow("2", "切换过来",
                "在任意输入框唤出键盘，长按空格或点键盘右下角的键盘图标，切到本输入法。"));
        box.addView(stepRow("3", "打拼音看英文",
                "输入 nihao，候选里「你好」下面就是 hello。点候选上屏中文，长按候选上屏英文；"
                        + "左上角「中 / EN」一键切换点候选出中文还是英文。"));
        return box;
    }

    private View stepRow(String num, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);

        TextView badge = label(num, 12f, 0xFFFFFFFF);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(skin.tinted(theme.accent, 20));
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        row.addView(badge);

        LinearLayout col = column();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(12);
        col.setLayoutParams(clp);
        TextView t = label(title, 14f, theme.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        TextView d = label(desc, 12f, theme.textDim);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        d.setLayoutParams(dlp);
        col.addView(d);

        row.addView(col);
        return row;
    }

    // ----------------------------------------------------------- 试打演示

    private View demoCard() {
        LinearLayout box = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = label("先在这儿试试", 14f, theme.text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);
        head.addView(actionButton("清空", false, new View.OnClickListener() {
            public void onClick(View v) {
                demoPinyin.setLength(0);
                demoResult.setLength(0);
                refreshDemo();
            }
        }));
        box.addView(head);

        TextView tip = label("这是应用自带的小键盘，和系统当前用哪个输入法无关；"
                + "候选是点一下上屏中文、长按上屏英文。", 11.5f, theme.textDim);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        tlp.bottomMargin = dp(10);
        tip.setLayoutParams(tlp);
        box.addView(tip);

        demoPinyinView = label("输入：", 15f, theme.text);
        box.addView(demoPinyinView);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(8);
        hs.setLayoutParams(hlp);
        demoBar = new LinearLayout(this);
        demoBar.setOrientation(LinearLayout.HORIZONTAL);
        demoBar.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(demoBar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hs);

        box.addView(padRow(PAD1, 0));
        box.addView(padRow(PAD2, 0));
        box.addView(padRow(PAD3, 1));

        demoResultView = label("上屏结果：（还没有）", 13f, theme.textDim);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        demoResultView.setLayoutParams(rlp);
        box.addView(demoResultView);
        return box;
    }

    private View padRow(String[] keys, int withDelete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        for (int i = 0; i < keys.length; i++) {
            row.addView(padKey(keys[i], 1f, Skin.KEY));
        }
        if (withDelete == 1) {
            row.addView(padKey("⌫", 1.6f, Skin.KEY_SPECIAL));
        }
        return row;
    }

    private TextView padKey(final String text, float weight, int style) {
        final TextView tv = label(text, 16f, theme.text);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(skin.key(style));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if ("⌫".equals(text)) {
                    if (demoPinyin.length() > 0) {
                        demoPinyin.deleteCharAt(demoPinyin.length() - 1);
                    }
                } else {
                    demoPinyin.append(text);
                    demoAssoc = null;
                }
                refreshDemo();
            }
        });
        return tv;
    }

    private void refreshDemo() {
        if (demoBar == null) {
            return;
        }
        demoBar.removeAllViews();
        String input = demoPinyin.toString();
        demoPinyinView.setText("输入：" + (input.length() == 0 ? "—" : input));

        if (demoResult.length() == 0) {
            demoResultView.setText("上屏结果：（还没有）");
        } else {
            demoResultView.setText("上屏结果：" + demoResult);
        }

        if (input.length() == 0) {
            if (demoAssoc != null && demoAssoc.size() > 0) {
                for (int i = 0; i < demoAssoc.size() && i < 12; i++) {
                    demoBar.addView(demoChip(demoAssoc.get(i), i));
                }
                return;
            }
            demoBar.addView(label("点下面的字母打拼音，比如 nihao / woaini / xiexie", 12f, theme.textDim));
            return;
        }
        if (dict == null) {
            demoBar.addView(label(dictLoading
                    ? "中英词典加载中…"
                    : "中英词典加载失败，崩溃栈已写入 Download/biime-crash-*.txt", 12f, theme.textDim));
            return;
        }

        List<Candidate> list = Candidates.build(this, dict, input, false);
        if (list.size() > 0 && list.get(0).matchedKey != null
                && !list.get(0).matchedKey.equals(input)) {
            demoBar.addView(label("≈" + list.get(0).matchedKey, 10.5f, theme.textDim));
        }
        if (list.isEmpty()) {
            demoBar.addView(label("没有匹配的中文，会原样上屏 " + input, 12f, theme.textDim));
        }
        for (int i = 0; i < list.size() && i < 20; i++) {
            demoBar.addView(demoChip(list.get(i), i));
        }
    }

    private View demoChip(final Candidate c, final int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(skin.card());
        box.setPadding(dp(11), dp(6), dp(11), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        box.setLayoutParams(lp);

        TextView zh = label(c.chinese(false), 18f, theme.text);
        zh.setSingleLine(true);
        if (index == 0) {
            zh.setTypeface(Typeface.DEFAULT_BOLD);
        }
        TextView en = label(c.english.length() == 0 ? "—" : c.english, 10.5f, theme.english);
        en.setSingleLine(true);
        en.setEllipsize(TextUtils.TruncateAt.END);
        en.setMaxWidth(dp(150));
        box.addView(zh);
        box.addView(en);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                demoResult.append(c.chinese(false));
                demoAssoc = Candidates.associate(MainActivity.this, c.chinese(false), 10);
                if (demoAssoc != null && demoAssoc.isEmpty()) {
                    demoAssoc = null;
                }
                demoPinyin.setLength(0);
                refreshDemo();
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                demoResult.append(c.englishForOutput(false));
                demoPinyin.setLength(0);
                toast("长按候选 → 上屏英文");
                refreshDemo();
                return true;
            }
        });
        return box;
    }

    // --------------------------------------------------------- 社区扩展包

    private View categoryRow() {
        // 分类变多了，横向滚动
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(12);
        hs.setLayoutParams(hlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(categoryChip("全部", Packs.CAT_ALL));
        row.addView(categoryChip("主题", Packs.CAT_THEME));
        row.addView(categoryChip("键盘", Packs.CAT_KEYS));
        row.addView(categoryChip("标点", Packs.CAT_PUNCT));
        row.addView(categoryChip("词典", Packs.CAT_DICT));
        row.addView(categoryChip("功能", Packs.CAT_FUNC));
        hs.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    private TextView categoryChip(final String text, final String cat) {
        final boolean on = cat.equals(category);
        TextView tv = label(text, 13f, on ? 0xFFFFFFFF : theme.text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setBackground(on ? skin.accentCard() : skin.card());
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                category = cat;
                setContentView(buildRoot());
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void renderPacks() {
        if (packList == null) {
            return;
        }
        packList.removeAllViews();
        List<Packs.Pack> list = Packs.byCategory(this, category);
        for (int i = 0; i < list.size(); i++) {
            packList.addView(packCard(list.get(i)));
        }
    }

    private View packCard(final Packs.Pack p) {
        LinearLayout box = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView icon = label(p.icon, 19f, theme.text);
        icon.setGravity(Gravity.CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        top.addView(icon);

        LinearLayout col = column();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(4);
        clp.rightMargin = dp(8);
        col.setLayoutParams(clp);

        TextView name = label(p.name, 15f, theme.text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(name);
        TextView desc = label(p.desc, 11.5f, theme.textDim);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        desc.setLayoutParams(dlp);
        col.addView(desc);

        String state = Packs.stateLabel(this, p);
        boolean active = "使用中".equals(state) || "已启用".equals(state);
        TextView badge = label(state, 11f, active ? 0xFFFFFFFF : theme.text);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(active ? skin.accentCard()
                : skin.outlined(theme.chip, theme.accent, 8));
        top.addView(badge);

        box.addView(top);

        if (p.theme != null) {
            box.addView(themePreview(p.theme));
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        bottom.setLayoutParams(blp);

        String metaText = p.typeLabel() + " · " + p.author + " · " + p.sizeLabel
                + (p.imported ? " · 本地导入" : " · 内置");
        if (p.entryCount() > 0) {
            metaText += " · " + p.entryCount() + " 条词条";
        }
        if (p.keys != null && p.keys.keyRow != null) {
            metaText += " · " + p.keys.keyRow.size() + " 个快捷短语";
        }
        if (p.punct != null) {
            int slots = 0;
            if (p.punct.comma != null) slots++;
            if (p.punct.period != null) slots++;
            if (p.punct.question != null) slots++;
            if (p.punct.exclaim != null) slots++;
            if (p.punct.page1 != null) slots += p.punct.page1.size();
            if (p.punct.page2 != null) slots += p.punct.page2.size();
            if (p.punct.page3 != null) slots += p.punct.page3.size();
            metaText += " · " + slots + " 个自定义标点";
        }
        TextView meta = label(metaText, 10.5f, Skin.alpha(theme.textDim, 0.85f));
        meta.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(meta);

        if (p.imported) {
            TextView del = label("删除", 11f, theme.textDim);
            del.setGravity(Gravity.CENTER);
            del.setPadding(dp(12), dp(6), dp(12), dp(6));
            del.setBackground(skin.outlined(theme.chip, theme.chipBorder, 8));
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    confirmDelete(p);
                }
            });
            bottom.addView(del);
        }
        box.addView(bottom);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onPackClick(p);
            }
        });
        return box;
    }

    private View themePreview(Packs.Theme t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);

        // 一小块键盘预览：底色 + 两个键帽
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackground(skin.outlined(t.panel, 0, 8));
        strip.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(10);
        strip.setLayoutParams(slp);

        TextView k1 = label("你好", 12f, t.text);
        k1.setGravity(Gravity.CENTER);
        k1.setBackground(skin.outlined(t.key, 0, 6));
        k1.setPadding(dp(10), dp(5), dp(10), dp(5));
        strip.addView(k1);

        TextView k2 = label("hello", 12f, t.english);
        k2.setGravity(Gravity.CENTER);
        k2.setBackground(skin.outlined(t.keySpecial, 0, 6));
        k2.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams k2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        k2lp.leftMargin = dp(6);
        k2.setLayoutParams(k2lp);
        strip.addView(k2);
        row.addView(strip);

        row.addView(dot(t.accent, 14));
        row.addView(dot(t.chipBorder, 14));
        return row;
    }

    private View dot(int color, int sizeDp) {
        View v = new View(this);
        v.setBackground(skin.tinted(color, sizeDp / 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private void onPackClick(Packs.Pack p) {
        if (p.is(Packs.CAT_THEME)) {
            if (p.id.equals(Packs.activeThemeId(this))) {
                toast("已经是「" + p.name + "」了");
                return;
            }
            Packs.setActiveTheme(this, p.id);
            theme = Packs.activeTheme(this);
            skin = new Skin(this, theme, Packs.tuning(this).keyRadius);
            getWindow().setBackgroundDrawable(new ColorDrawable(theme.panel));
            setContentView(buildRoot());
            toast("已切换到「" + p.name + "」，键盘下次弹出就是新配色");
            return;
        }
        boolean on = !Packs.isEnabled(this, p.id);
        Packs.setEnabled(this, p.id, on);
        if (on) {
            String what = p.is(Packs.CAT_DICT) ? "，打字时直接进候选" : "，键盘下次弹出生效";
            if (p.is(Packs.CAT_PUNCT)) {
                what = "，符号页和标点键都换掉了";
            }
            toast("已启用「" + p.name + "」" + what);
        } else {
            toast("已停用「" + p.name + "」");
        }
        renderPacks();
        refreshDemo();
    }

    // ----------------------------------------------------------- 导入扩展包

    private View importRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);
        row.addView(actionButton("＋ 导入文件", true, new View.OnClickListener() {
            public void onClick(View v) {
                importFromFile();
            }
        }));
        row.addView(actionButton("从剪贴板导入", false, new View.OnClickListener() {
            public void onClick(View v) {
                importFromClipboard();
            }
        }));
        return row;
    }

    private void importFromFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable e) {
            CrashLog.save(this, "import-open", e);
            toast("打不开文件选择器，可以改用「从剪贴板导入」");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try {
            InputStream in = getContentResolver().openInputStream(data.getData());
            if (in == null) {
                toast("读不到这个文件");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            doImport(new String(bos.toByteArray(), "UTF-8"));
        } catch (Throwable e) {
            CrashLog.save(this, "import-read", e);
            toast("读取文件失败：" + e.getMessage());
        }
    }

    private void importFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("剪贴板里没有内容");
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            doImport(cs == null ? null : cs.toString());
        } catch (Throwable e) {
            CrashLog.save(this, "import-clip", e);
            toast("读剪贴板失败：" + e.getMessage());
        }
    }

    private void doImport(String text) {
        try {
            Packs.Pack p = Packs.importPack(this, text);
            if (p == null) {
                toast("导入失败：包内容不完整");
                return;
            }
            if (!packsExpanded) {
                setPacksExpanded(true);   // 导入后自动展开，让用户看到新包
            }
            renderPacks();
            refreshDemo();
            toast("已导入「" + p.name + "」（" + p.typeLabel() + "）并自动启用");
        } catch (IllegalArgumentException e) {
            toast("导入失败：" + e.getMessage());
        } catch (Throwable e) {
            CrashLog.save(this, "import", e);
            toast("导入失败：" + e.getMessage());
        }
    }

    private void confirmDelete(final Packs.Pack p) {
        new AlertDialog.Builder(this)
                .setTitle("删除扩展包")
                .setMessage("确定删除「" + p.name + "」？它带来的配色 / 词条 / 开关都会一并失效。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Packs.deletePack(MainActivity.this, p.id);
                        renderPacks();
                        refreshDemo();
                        toast("已删除「" + p.name + "」");
                    }
                })
                .show();
    }

    private View footerView() {
        TextView tv = label("词典：CC-CEDICT（CC BY-SA 4.0）· 词频：jieba（MIT）\n"
                + "完全离线运行，不联网、不收集任何输入内容。", 10.5f,
                Skin.alpha(theme.textDim, 0.8f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        tv.setLayoutParams(lp);
        return tv;
    }

    // --------------------------------------------------------------- 状态

    private void updateStatus() {
        if (statusText == null) {
            return;
        }
        try {
            boolean enabled = isImeEnabled();
            String cur = currentImeIdOrNull();
            String line2;
            if (cur != null) {
                line2 = cur.startsWith(getPackageName())
                        ? "② 正在使用本输入法 ✅"
                        : "② 当前用的是别的输入法";
            } else {
                // Android 14 起读不到 default_input_method，用输入法自己写的心跳判断
                long last = Packs.imeLastActive(this);
                boolean recent = last > 0 && System.currentTimeMillis() - last < 10 * 60 * 1000L;
                line2 = recent ? "② 最近用过本输入法 ✅" : "② 还没用过本输入法";
            }
            statusText.setText((enabled ? "① 已启用 ✅" : "① 还没启用 ❌") + "\n" + line2);
        } catch (Throwable e) {
            CrashLog.save(this, "status", e);
            statusText.setText("状态读取失败（已记录），不影响正常使用");
        }
    }

    /**
     * 是否已在系统里启用。
     * 注意：不能用 Settings.Secure.ENABLED_INPUT_METHODS —— Android 14 起
     * 该 key 只对 targetSdk ≤ 33 的应用可读，读它会抛 SecurityException
     * （这正是之前一打开就闪退的原因）。InputMethodManager 的公开 API 没有这个限制。
     */
    private boolean isImeEnabled() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<InputMethodInfo> list = imm.getEnabledInputMethodList();
                for (int i = 0; i < list.size(); i++) {
                    if (getPackageName().equals(list.get(i).getPackageName())) {
                        return true;
                    }
                }
                return false;
            }
        } catch (Throwable e) {
            CrashLog.save(this, "status-enabled", e);
        }
        return false;
    }

    /** 当前输入法 id；Android 14 + targetSdk 34 下系统不允许读，返回 null。 */
    private String currentImeIdOrNull() {
        try {
            return Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (Throwable e) {
            return null;
        }
    }
}
