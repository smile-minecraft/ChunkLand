package com.smile.chunkland.enterleave;

import java.util.Objects;

/**
 * One prompt to show for a boundary crossing. Positions already carry the
 * display names resolved from the same snapshot that detected the crossing,
 * so the render path never re-reads the registry.
 */
public record EnterLeaveNotice(NoticeKind kind, EnterLeavePosition position) {

    public EnterLeaveNotice {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
    }
}
