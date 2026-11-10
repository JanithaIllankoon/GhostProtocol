package com.example.ghostprotocol;

import android.graphics.Color;

/**
 * Centralised colour palette for the Ghost Protocol UI.
 * All activities reference these constants rather than hardcoding colours —
 * retheming the whole app is a one-file change. themes.xml mirrors these.
 *
 * The palette is deliberately quiet: near-black surfaces with a hairline
 * outline, grey text tiers and a single mint accent taken from the app icon.
 * Warn/danger are reserved for the status card and destructive actions.
 */
public final class Theme {
    private Theme() {}

    // ---- Surfaces ----
    public static final int BG             = Color.parseColor("#0A0C0B"); // window
    public static final int SURFACE        = Color.parseColor("#131715"); // cards
    public static final int SURFACE_HIGH   = Color.parseColor("#1C211F"); // secondary buttons, inputs
    public static final int OUTLINE        = Color.parseColor("#20262A"); // card hairline
    public static final int DIVIDER        = Color.parseColor("#1F2523");

    // ---- Accent ----
    public static final int ACCENT         = Color.parseColor("#3DDC97"); // mint, from the icon
    public static final int ACCENT_SOFT    = Color.parseColor("#14291F"); // icon chips, tags
    public static final int ON_ACCENT      = Color.parseColor("#06140E"); // text on accent

    // ---- Status ----
    public static final int WARN           = Color.parseColor("#E8B44C");
    public static final int DANGER         = Color.parseColor("#E5645B");

    // ---- Text tiers ----
    public static final int TEXT_PRIMARY   = Color.parseColor("#EEF2F0");
    public static final int TEXT_SECONDARY = Color.parseColor("#9AA39F");
    public static final int TEXT_TERTIARY  = Color.parseColor("#626B67");

    /** {@code color} at the given opacity (0–1). */
    public static int alpha(int color, float opacity) {
        return Color.argb(Math.round(opacity * 255),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
