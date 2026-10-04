package com.smile.chunkland.selection;

import java.util.UUID;

/** Resolves the semantic colour once when an occupied preview is created. */
@FunctionalInterface
public interface SelectionPreviewColorResolver {
    SelectionPreviewColor resolve(UUID actor, SelectionLandContext land, SelectionPoint target);

    static SelectionPreviewColorResolver blocked() {
        return (actor, land, target) -> SelectionPreviewColor.BLOCKED;
    }
}
