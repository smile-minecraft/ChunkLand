package com.smile.chunkland.selection;

/** Semantic colours for an occupied-land boundary preview. */
public enum SelectionPreviewColor {
    /** The operating player owns the target land. */
    OWN,
    /** Entry and at least one third- or fourth-tier action are effective. */
    ACCESSIBLE,
    /** Entry, an action sample, or the source state is unavailable or denied. */
    BLOCKED
}
