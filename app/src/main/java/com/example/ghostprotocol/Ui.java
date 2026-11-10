package com.example.ghostprotocol;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Small view factory shared by every screen. The app builds its UI in code,
 * so these helpers keep spacing, shapes and typography consistent.
 *
 * Text style: screen titles, section labels, row titles, buttons and dialog
 * titles use Title Case; descriptions and hints use normal sentence case.
 */
public final class Ui {
    private Ui() {}

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String TAG_TITLE = "row-title";
    private static final String TAG_SUBTITLE = "row-subtitle";
    private static final String TAG_ICON_ROW = "icon-row";
    private static final String TAG_VALUE = "row-value";

    public static int dp(Context ctx, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(Context ctx, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    /** Background with a touch ripple clipped to the same rounded shape. */
    public static Drawable pressable(Context ctx, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),
                rounded(ctx, color, radiusDp), rounded(ctx, Color.WHITE, radiusDp));
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static TextView text(Context ctx, CharSequence value, float sizeSp, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    public static TextView bold(TextView tv) {
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    public static ImageView icon(Context ctx, int iconRes, int color, int sizeDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setLayoutParams(lp(dp(ctx, sizeDp), dp(ctx, sizeDp)));
        return iv;
    }

    /** A rounded tinted square holding an icon, used at the start of rows. */
    public static View iconChip(Context ctx, int iconRes, int iconColor, int bgColor, int sizeDp) {
        FrameLayout chip = new FrameLayout(ctx);
        chip.setBackground(rounded(ctx, bgColor, sizeDp * 0.3f));
        ImageView iv = icon(ctx, iconRes, iconColor, Math.round(sizeDp * 0.55f));
        chip.addView(iv, new FrameLayout.LayoutParams(
                dp(ctx, sizeDp * 0.55f), dp(ctx, sizeDp * 0.55f), Gravity.CENTER));
        chip.setLayoutParams(lp(dp(ctx, sizeDp), dp(ctx, sizeDp)));
        return chip;
    }

    // -------------------------------------------------------------------------
    // Screen scaffold
    // -------------------------------------------------------------------------

    /**
     * Sets up an edge-to-edge scrolling screen and returns the column that
     * content should be added to. Child 0 of the column is always the top bar.
     *
     * Home ({@code showBack} false): the title sits in the top bar.
     * Sub-screens: a round back button sits in the top bar and a large title
     * with its description sits underneath.
     */
    public static LinearLayout screen(AppCompatActivity activity, String title,
                                      String subtitle, boolean showBack) {
        EdgeToEdge.enable(activity,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Theme.BG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(MATCH, WRAP));

        // Solid strip behind the status bar so scrolled content doesn't run
        // underneath the clock and icons.
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Theme.BG);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        View statusScrim = new View(activity);
        statusScrim.setBackgroundColor(Theme.BG);
        root.addView(statusScrim, new FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP));

        int side = dp(activity, 20);
        int top = dp(activity, 8);
        int bottom = dp(activity, 40);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            column.setPadding(side + bars.left, top + bars.top,
                    side + bars.right, bottom + bars.bottom);
            statusScrim.getLayoutParams().height = bars.top;
            statusScrim.requestLayout();
            return WindowInsetsCompat.CONSUMED;
        });

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(activity, 60));
        column.addView(bar, lp(MATCH, WRAP));

        if (showBack) {
            FrameLayout back = new FrameLayout(activity);
            back.setBackground(pressable(activity, Theme.SURFACE, 22));
            back.addView(icon(activity, R.drawable.ic_back, Theme.TEXT_PRIMARY, 22),
                    new FrameLayout.LayoutParams(dp(activity, 22), dp(activity, 22), Gravity.CENTER));
            back.setContentDescription("Back");
            back.setOnClickListener(v -> activity.finish());
            bar.addView(back, lp(dp(activity, 44), dp(activity, 44)));
            bar.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));

            TextView titleView = bold(text(activity, title, 30, Theme.TEXT_PRIMARY));
            titleView.setLetterSpacing(-0.01f);
            LinearLayout.LayoutParams tp = lp(MATCH, WRAP);
            tp.topMargin = dp(activity, 18);
            column.addView(titleView, tp);
        } else {
            TextView titleView = bold(text(activity, title, 26, Theme.TEXT_PRIMARY));
            titleView.setLetterSpacing(-0.01f);
            bar.addView(titleView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }

        if (subtitle != null) {
            TextView sub = text(activity, subtitle, 15, Theme.TEXT_SECONDARY);
            LinearLayout.LayoutParams sp = lp(MATCH, WRAP);
            sp.topMargin = dp(activity, showBack ? 6 : 0);
            sp.bottomMargin = dp(activity, 4);
            column.addView(sub, sp);
        }

        activity.setContentView(root);
        return column;
    }

    /** Adds a text action to the right side of a screen's top bar (e.g. "Clear All"). */
    public static TextView headerAction(LinearLayout column, String label, View.OnClickListener onClick) {
        Context ctx = column.getContext();
        LinearLayout bar = (LinearLayout) column.getChildAt(0);
        TextView action = bold(text(ctx, label, 14, Theme.ACCENT));
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        action.setBackground(pressable(ctx, Theme.SURFACE, 22));
        action.setOnClickListener(onClick);
        bar.addView(action, lp(WRAP, dp(ctx, 44)));
        return action;
    }

    // -------------------------------------------------------------------------
    // Building blocks
    // -------------------------------------------------------------------------

    public static TextView sectionLabel(Context ctx, String label) {
        TextView tv = bold(text(ctx, label, 15, Theme.TEXT_SECONDARY));
        tv.setPadding(dp(ctx, 4), dp(ctx, 28), 0, dp(ctx, 10));
        return tv;
    }

    public static TextView hint(Context ctx, String value) {
        TextView tv = text(ctx, value, 13, Theme.TEXT_TERTIARY);
        tv.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), 0);
        return tv;
    }

    /** A rounded, outlined surface that stacks rows; dividers are inserted by {@link #addRow}. */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(ctx, Theme.SURFACE, 20);
        bg.setStroke(dp(ctx, 1), Theme.OUTLINE);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setLayoutParams(lp(MATCH, WRAP));
        return card;
    }

    public static void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            View divider = new View(card.getContext());
            divider.setBackgroundColor(Theme.DIVIDER);
            LinearLayout.LayoutParams dlp = lp(MATCH, 1);
            // Line up the divider with the row's text, past any leading icon.
            dlp.setMarginStart(dp(card.getContext(), TAG_ICON_ROW.equals(row.getTag()) ? 70 : 16));
            card.addView(divider, dlp);
        }
        card.addView(row, lp(MATCH, WRAP));
    }

    public static LinearLayout row(Context ctx, CharSequence title, CharSequence subtitle,
                                   CharSequence value, int titleLines, View.OnClickListener onClick) {
        return row(ctx, 0, title, subtitle, value, titleLines, onClick);
    }

    /**
     * A list row: optional leading icon, title, optional subtitle, optional
     * trailing value and a chevron when clickable. {@code titleLines} caps the
     * title (0 = no cap).
     */
    public static LinearLayout row(Context ctx, int iconRes, CharSequence title, CharSequence subtitle,
                                   CharSequence value, int titleLines, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 64));
        row.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14));

        if (iconRes != 0) {
            View chip = iconChip(ctx, iconRes, Theme.ACCENT, Theme.ACCENT_SOFT, 40);
            ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMarginEnd(dp(ctx, 14));
            row.addView(chip);
            row.setTag(TAG_ICON_ROW);
        }

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = text(ctx, title, 16, Theme.TEXT_PRIMARY);
        titleView.setTag(TAG_TITLE);
        if (iconRes != 0) bold(titleView);
        if (titleLines > 0) {
            titleView.setMaxLines(titleLines);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
        }
        texts.addView(titleView);

        if (subtitle != null) {
            TextView sub = text(ctx, subtitle, 13, Theme.TEXT_SECONDARY);
            sub.setTag(TAG_SUBTITLE);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(ctx, 2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        if (value != null) {
            TextView v = text(ctx, value, 15, Theme.TEXT_SECONDARY);
            v.setTag(TAG_VALUE);
            v.setSingleLine(true);
            v.setPadding(dp(ctx, 12), 0, 0, 0);
            row.addView(v);
        }

        if (onClick != null) {
            ImageView chevron = icon(ctx, R.drawable.ic_chevron, Theme.TEXT_TERTIARY, 20);
            ((LinearLayout.LayoutParams) chevron.getLayoutParams()).setMarginStart(dp(ctx, 8));
            row.addView(chevron);
            row.setBackground(pressable(ctx, Theme.SURFACE, 0));
            row.setOnClickListener(onClick);
        }
        return row;
    }

    public static TextView titleOf(View row) {
        return row.findViewWithTag(TAG_TITLE);
    }

    public static TextView subtitleOf(View row) {
        return row.findViewWithTag(TAG_SUBTITLE);
    }

    /** The trailing value of a row built with a non-null {@code value}. */
    public static TextView valueOf(View row) {
        return row.findViewWithTag(TAG_VALUE);
    }

    /** A round icon-only button, e.g. the settings gear in the home top bar. */
    public static View iconButton(Context ctx, int iconRes, String description,
                                  View.OnClickListener onClick) {
        FrameLayout b = new FrameLayout(ctx);
        b.setBackground(pressable(ctx, Theme.SURFACE, 22));
        b.addView(icon(ctx, iconRes, Theme.TEXT_PRIMARY, 22),
                new FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22), Gravity.CENTER));
        b.setContentDescription(description);
        b.setOnClickListener(onClick);
        b.setLayoutParams(lp(dp(ctx, 44), dp(ctx, 44)));
        return b;
    }

    /** An accent "+ Add …" row placed at the bottom of a list card. */
    public static View addRowButton(Context ctx, String label, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 56));
        row.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        ImageView plus = icon(ctx, R.drawable.ic_add, Theme.ACCENT, 20);
        ((LinearLayout.LayoutParams) plus.getLayoutParams()).setMarginEnd(dp(ctx, 12));
        row.addView(plus);
        row.addView(bold(text(ctx, label, 15, Theme.ACCENT)));
        row.setBackground(pressable(ctx, Theme.SURFACE, 0));
        row.setOnClickListener(onClick);
        return row;
    }

    /** Full-width pill button. Filled with the accent when {@code primary}. */
    public static TextView button(Context ctx, String label, boolean primary, View.OnClickListener onClick) {
        TextView b = bold(text(ctx, label, 15, primary ? Theme.ON_ACCENT : Theme.TEXT_PRIMARY));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(ctx, 52));
        b.setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0);
        b.setBackground(pressable(ctx, primary ? Theme.ACCENT : Theme.SURFACE_HIGH, 26));
        b.setOnClickListener(onClick);
        b.setLayoutParams(lp(MATCH, WRAP));
        return b;
    }

    public static TextView emptyRow(Context ctx, String message) {
        TextView tv = text(ctx, message, 14, Theme.TEXT_TERTIARY);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 20), dp(ctx, 32), dp(ctx, 20), dp(ctx, 32));
        return tv;
    }

    /** Small filled tag, e.g. the reply type in history. */
    public static TextView tag(Context ctx, String label) {
        TextView tv = bold(text(ctx, label, 11, Theme.ACCENT));
        tv.setBackground(rounded(ctx, Theme.ACCENT_SOFT, 8));
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 4));
        return tv;
    }

    /** A filled circle used as a status indicator. */
    public static View dot(Context ctx, int color, int sizeDp) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(lp(dp(ctx, sizeDp), dp(ctx, sizeDp)));
        return v;
    }

    public static void setDotColor(View dot, int color) {
        ((GradientDrawable) dot.getBackground()).setColor(color);
    }

    // -------------------------------------------------------------------------
    // Dialog helpers
    // -------------------------------------------------------------------------

    public static EditText input(Context ctx, String value, String hint, boolean multiline) {
        EditText input = new EditText(ctx);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(16);
        input.setTextColor(Theme.TEXT_PRIMARY);
        input.setHintTextColor(Theme.TEXT_TERTIARY);
        GradientDrawable bg = rounded(ctx, Theme.BG, 14);
        bg.setStroke(dp(ctx, 1), Theme.OUTLINE);
        input.setBackground(bg);
        int p = dp(ctx, 14);
        input.setPadding(p, p, p, p);
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMinLines(3);
            input.setGravity(Gravity.TOP | Gravity.START);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            input.setSingleLine(true);
        }
        if (value != null) input.setSelection(input.getText().length());
        return input;
    }

    /** Wraps dialog content with the standard side padding, optionally labelling each field. */
    public static LinearLayout dialogBody(Context ctx, Object... labelsAndViews) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), 0);
        for (Object item : labelsAndViews) {
            if (item instanceof String) {
                TextView label = bold(text(ctx, (String) item, 13, Theme.TEXT_SECONDARY));
                label.setPadding(dp(ctx, 2), body.getChildCount() == 0 ? 0 : dp(ctx, 16), 0, dp(ctx, 6));
                body.addView(label);
            } else {
                body.addView((View) item, lp(MATCH, WRAP));
            }
        }
        return body;
    }
}
