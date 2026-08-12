package com.lucentvpn.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Five colours, no more.
 *
 * The aqua is spent in exactly one place -- a live tunnel -- so that "am I
 * protected?" is answerable from across the room without reading a word. If it
 * were also used for buttons and links it would stop meaning anything.
 */
object LucentColors {

    /** Canvas. Deep blue-black, not neutral black, so surfaces can lift. */
    val DeepInk = Color(0xFF0B111A)

    /** Raised surfaces: cards, sheets, list rows. */
    val Slate = Color(0xFF161E2B)

    /** Borders and quiet dividers. */
    val SlateEdge = Color(0xFF243044)

    /** Primary text on dark. Warm off-white; pure white is harsh here. */
    val Bone = Color(0xFFEDEFF3)

    /** Secondary text, labels, inactive glyphs. */
    val Mist = Color(0xFF8794AB)

    /** THE accent. Reserved for an established tunnel. */
    val Aqua = Color(0xFF43E0C8)

    /** Trouble: warnings, failures, destructive confirmation. */
    val Amber = Color(0xFFE8A33D)

    // ---- Light mode -------------------------------------------------------
    // The same palette inverted. Aqua darkens slightly to stay legible on
    // paper-white; the raw aqua fails contrast on light surfaces.

    val Paper = Color(0xFFF7F8FA)
    val PaperRaised = Color(0xFFFFFFFF)
    val PaperEdge = Color(0xFFE1E5EC)
    val Charcoal = Color(0xFF141A24)
    val CharcoalMist = Color(0xFF5C6779)
    val AquaDeep = Color(0xFF0E9E88)
    val AmberDeep = Color(0xFFB4741A)
}
