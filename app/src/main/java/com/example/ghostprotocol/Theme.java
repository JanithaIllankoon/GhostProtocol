package com.example.ghostprotocol;

import android.graphics.Color;

/**
 * Centralised colour palette for the Ghost Protocol UI.
 * All activities reference these constants rather than hardcoding colours —
 * retheming the whole app is a one-file change.
 */
public final class Theme {
    private Theme() {}

    // ---- Backgrounds ----
    public static final int BG            = Color.parseColor("#0A0A0A"); // root
    public static final int CARD_BG       = Color.parseColor("#111111"); // list cards
    public static final int BUTTON_BG     = Color.parseColor("#141414"); // secondary buttons
    public static final int INPUT_BG      = Color.parseColor("#1A1A1A"); // text fields
    public static final int EDIT_BG       = Color.parseColor("#1A1A00"); // edit action
    public static final int DELETE_BG     = Color.parseColor("#1A0000"); // delete action
    public static final int PRIMARY_BG    = Color.parseColor("#003300"); // active/go state
    public static final int ERROR_BG      = Color.parseColor("#330000"); // error state
    public static final int WARN_BG       = Color.parseColor("#332200"); // degraded state

    // ---- Accents ----
    public static final int PRIMARY       = Color.parseColor("#00FF41"); // electric green
    public static final int WARN          = Color.parseColor("#FFCC00"); // yellow
    public static final int DANGER        = Color.parseColor("#FF4444"); // red
    public static final int ACCENT_CALL   = Color.parseColor("#00CFFF"); // cyan — calls / keywords
    public static final int ACCENT_SPAM   = Color.parseColor("#FFAA00"); // orange — spam

    // ---- Text tiers ----
    public static final int TEXT_PRIMARY    = Color.WHITE;
    public static final int TEXT_SECONDARY  = Color.parseColor("#AAAAAA");
    public static final int TEXT_TERTIARY   = Color.parseColor("#555555");
    public static final int TEXT_QUATERNARY = Color.parseColor("#444444");
    public static final int TEXT_MUTED      = Color.GRAY;
    public static final int TEXT_DK         = Color.DKGRAY;
}
