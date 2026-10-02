package com.dsh.biime;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * 把主题色变成实际用的 Drawable。
 * 键盘和启动页共用同一套，所以换主题是「键盘 + 应用」一起变。
 */
public final class Skin {

    public static final int KEY = 0;
    public static final int KEY_SPECIAL = 1;
    public static final int KEY_ACCENT = 2;

    private final Context ctx;
    public final Packs.Theme theme;
    private final int radiusDp;

    public Skin(Context ctx, Packs.Theme theme) {
        this(ctx, theme, 9);
    }

    public Skin(Context ctx, Packs.Theme theme, int radiusDp) {
        this.ctx = ctx.getApplicationContext();
        this.theme = theme;
        this.radiusDp = radiusDp < 0 ? 0 : (radiusDp > 24 ? 24 : radiusDp);
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 按键底：普通 / 功能 / 强调，按下时自动提亮。 */
    public Drawable key(int style) {
        int base;
        if (style == KEY_ACCENT) {
            base = theme.accent;
        } else if (style == KEY_SPECIAL) {
            base = theme.keySpecial;
        } else {
            base = theme.key;
        }
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(lift(base, 0.22f), radiusDp, 0));
        s.addState(new int[]{}, round(base, radiusDp, 0));
        return s;
    }

    /** 候选条 / 卡片底。 */
    public Drawable card() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(theme.chip, 8, theme.accent));
        s.addState(new int[]{}, round(theme.chip, 8, theme.chipBorder));
        return s;
    }

    /** 高亮卡片（当前选中的分类、使用中的包）。 */
    public Drawable accentCard() {
        GradientDrawable g = round(theme.chip, 8, theme.accent);
        return g;
    }

    public Drawable tinted(int color, int radiusDp) {
        return round(color, radiusDp, 0);
    }

    public Drawable outlined(int color, int borderColor, int radiusDp) {
        return round(color, radiusDp, borderColor);
    }

    private GradientDrawable round(int color, int radiusDp, int borderColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (borderColor != 0) {
            g.setStroke(dp(1), borderColor);
        }
        return g;
    }

    /** 朝白色混合，得到「按下 / 悬停」的提亮色。 */
    public static int lift(int color, float f) {
        int a = (color >>> 24) & 0xff;
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = color & 0xff;
        r = (int) (r + (255 - r) * f);
        g = (int) (g + (255 - g) * f);
        b = (int) (b + (255 - b) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 带透明度，用于分隔线 / 次级背景。 */
    public static int alpha(int color, float f) {
        int a = (int) (((color >>> 24) & 0xff) * f);
        return (a << 24) | (color & 0x00ffffff);
    }
}
