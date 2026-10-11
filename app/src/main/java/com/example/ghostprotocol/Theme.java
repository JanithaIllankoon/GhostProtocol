package com.example.ghostprotocol;

import android.graphics.Color;

/**
 * Centralised colour palette for the Ghost Protocol UI.
 * All activities reference these constants rather than hardcoding colours —
 * retheming the whole app is a one-file change.
 *
 * The palette is deliberately monochrome: near-black surfaces, grey text tiers
 * and a single mint accent taken from the app icon. Warn/danger are reserved
 * for the status indicator and destructive actions.
 */
public final class Theme {
    private Theme() {}

    // ---- Surfaces ----
    public static final int BG             = Color.parseColor("#0B0D0C"); // window
    public static final int SURFACE        = Color.parseColor("#151917"); // cards
    public static final int SURFACE_HIGH   = Color.parseColor("#1E2321"); // inputs, pressed, dialogs
    public static final int DIVIDER        = Color.parseColor("#252B28");

    // ---- Accent ----
    public static final int ACCENT         = Color.parseColor("#3DDC97"); // mint, from the icon
    public static final int ACCENT_DIM     = Color.parseColor("#163A2B"); // accent-tinted surface
    public static final int ON_ACCENT      = Color.parseColor("#06140E"); // text on accent

    // ---- Status ----
    public static final int WARN           = Color.parseColor("#E8B44C");
    public static final int DANGER         = Color.parseColor("#E5645B");

    // ---- Text tiers ----
    public static final int TEXT_PRIMARY   = Color.parseColor("#EEF2F0");
    public static final int TEXT_SECONDARY = Color.parseColor("#A3ABA7");
    public static final int TEXT_TERTIARY  = Color.parseColor("#6B736F");
}
