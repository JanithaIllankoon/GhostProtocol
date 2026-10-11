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
 */
public final class Ui {
    private Ui() {}

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT;

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
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
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

    // -------------------------------------------------------------------------
    // Screen scaffold
    // -------------------------------------------------------------------------

    /**
     * Sets up an edge-to-edge scrolling screen with a header and returns the
     * column that content should be added to. When {@code showBack} is true a
     * back arrow is shown that finishes the activity.
     */
    public static LinearLayout screen(AppCompatActivity activity, String title,
                                      String subtitle, boolean showBack) {
        EdgeToEdge.enable(activity,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Theme.BG);
        scroll.setFillViewport(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(MATCH, WRAP));

        int side = dp(activity, 20);
        int top = dp(activity, 12);
        int bottom = dp(activity, 32);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            column.setPadding(side + bars.left, top + bars.top,
                    side + bars.right, bottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Header row: [back] title ........ [action slot]
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(activity, 56));

        if (showBack) {
            TextView back = text(activity, "‹", 30, Theme.TEXT_PRIMARY);
            back.setGravity(Gravity.CENTER);
            back.setPadding(0, 0, 0, dp(activity, 4));
            back.setBackground(pressable(activity, Theme.BG, 20));
            back.setContentDescription("Back");
            back.setOnClickListener(v -> activity.finish());
            LinearLayout.LayoutParams bp = lp(dp(activity, 40), dp(activity, 40));
            bp.setMarginStart(-dp(activity, 10));
            bp.setMarginEnd(dp(activity, 4));
            header.addView(back, bp);
        }

        TextView titleView = bold(text(activity, title, showBack ? 22 : 26, Theme.TEXT_PRIMARY));
        header.addView(titleView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        column.addView(header, lp(MATCH, WRAP));

        if (subtitle != null) {
            TextView sub = text(activity, subtitle, 14, Theme.TEXT_SECONDARY);
            LinearLayout.LayoutParams sp = lp(MATCH, WRAP);
            sp.bottomMargin = dp(activity, 8);
            column.addView(sub, sp);
        }

        activity.setContentView(scroll);
        return column;
    }

    /** Adds a text action to the right side of a screen's header (e.g. "Clear"). */
    public static TextView headerAction(LinearLayout column, String label, View.OnClickListener onClick) {
        Context ctx = column.getContext();
        LinearLayout header = (LinearLayout) column.getChildAt(0);
        TextView action = bold(text(ctx, label, 14, Theme.ACCENT));
        int p = dp(ctx, 10);
        action.setPadding(p, p, p, p);
        action.setBackground(pressable(ctx, Theme.BG, 12));
        action.setOnClickListener(onClick);
        LinearLayout.LayoutParams ap = lp(WRAP, WRAP);
        ap.setMarginEnd(-p);
        header.addView(action, ap);
        return action;
    }

    // -------------------------------------------------------------------------
    // Building blocks
    // -------------------------------------------------------------------------

    public static TextView sectionLabel(Context ctx, String label) {
        TextView tv = bold(text(ctx, label.toUpperCase(), 12, Theme.TEXT_TERTIARY));
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(ctx, 4), dp(ctx, 24), 0, dp(ctx, 8));
        return tv;
    }

    public static TextView hint(Context ctx, String value) {
        TextView tv = text(ctx, value, 13, Theme.TEXT_TERTIARY);
        tv.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), 0);
        return tv;
    }

    /** A rounded surface that stacks rows; dividers are inserted by {@link #addRow}. */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(ctx, Theme.SURFACE, 18));
        card.setClipToOutline(true);
        card.setLayoutParams(lp(MATCH, WRAP));
        return card;
    }

    public static void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            View divider = new View(card.getContext());
            divider.setBackgroundColor(Theme.DIVIDER);
            LinearLayout.LayoutParams dlp = lp(MATCH, 1);
            dlp.setMarginStart(dp(card.getContext(), 16));
            card.addView(divider, dlp);
        }
        card.addView(row, lp(MATCH, WRAP));
    }

    /**
     * A list row: title, optional subtitle, optional trailing value and a
     * chevron when clickable. {@code titleLines} caps the title (0 = no cap).
     */
    public static LinearLayout row(Context ctx, CharSequence title, CharSequence subtitle,
                                   CharSequence value, int titleLines, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 60));
        row.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = text(ctx, title, 16, Theme.TEXT_PRIMARY);
        if (titleLines > 0) {
            titleView.setMaxLines(titleLines);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
        }
        texts.addView(titleView);

        if (subtitle != null) {
            TextView sub = text(ctx, subtitle, 13, Theme.TEXT_SECONDARY);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(ctx, 3), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        if (value != null) {
            TextView v = text(ctx, value, 14, Theme.TEXT_SECONDARY);
            v.setPadding(dp(ctx, 12), 0, 0, 0);
            row.addView(v);
        }

        if (onClick != null) {
            TextView chevron = text(ctx, "›", 22, Theme.TEXT_TERTIARY);
            chevron.setPadding(dp(ctx, 10), 0, 0, dp(ctx, 2));
            row.addView(chevron);
            row.setBackground(pressable(ctx, Theme.SURFACE, 0));
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /** A centred accent-coloured "+ Add …" row placed at the bottom of a card. */
    public static TextView addRowButton(Context ctx, String label, View.OnClickListener onClick) {
        TextView tv = bold(text(ctx, "+  " + label, 15, Theme.ACCENT));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setMinHeight(dp(ctx, 54));
        tv.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        tv.setBackground(pressable(ctx, Theme.SURFACE, 0));
        tv.setOnClickListener(onClick);
        return tv;
    }

    /** Full-width pill button. Filled with the accent when {@code primary}. */
    public static TextView button(Context ctx, String label, boolean primary, View.OnClickListener onClick) {
        TextView b = bold(text(ctx, label, 15, primary ? Theme.ON_ACCENT : Theme.TEXT_PRIMARY));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(ctx, 50));
        b.setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0);
        b.setBackground(pressable(ctx, primary ? Theme.ACCENT : Theme.SURFACE_HIGH, 25));
        b.setOnClickListener(onClick);
        b.setLayoutParams(lp(MATCH, WRAP));
        return b;
    }

    public static TextView emptyRow(Context ctx, String message) {
        TextView tv = text(ctx, message, 14, Theme.TEXT_TERTIARY);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 16), dp(ctx, 28), dp(ctx, 16), dp(ctx, 28));
        return tv;
    }

    /** Small outlined tag, e.g. the reply type in history. */
    public static TextView tag(Context ctx, String label) {
        TextView tv = text(ctx, label, 11, Theme.TEXT_SECONDARY);
        GradientDrawable bg = rounded(ctx, Color.TRANSPARENT, 8);
        bg.setStroke(dp(ctx, 1), Theme.DIVIDER);
        tv.setBackground(bg);
        tv.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 3));
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
        GradientDrawable bg = rounded(ctx, Theme.BG, 12);
        bg.setStroke(dp(ctx, 1), Theme.DIVIDER);
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
                TextView label = text(ctx, (String) item, 13, Theme.TEXT_SECONDARY);
                label.setPadding(dp(ctx, 2), body.getChildCount() == 0 ? 0 : dp(ctx, 16), 0, dp(ctx, 6));
                body.addView(label);
            } else {
                body.addView((View) item, lp(MATCH, WRAP));
            }
        }
        return body;
    }
}
