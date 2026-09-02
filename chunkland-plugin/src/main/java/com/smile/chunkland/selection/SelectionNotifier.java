package com.smile.chunkland.selection;

/** Pure notification sink; it must not render chat or otherwise touch Bukkit. */
@FunctionalInterface
public interface SelectionNotifier {
    void notify(SelectionNotification notification);

    static SelectionNotifier noop() {
        return notification -> { };
    }
}
