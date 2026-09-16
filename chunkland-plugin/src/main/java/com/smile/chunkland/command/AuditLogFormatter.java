package com.smile.chunkland.command;

import com.smile.chunkland.persistence.AuditEntry;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders a stored {@link AuditEntry} for chat output.
 *
 * <p>The stored entry never carries a locale: the effective player locale is
 * supplied on every call and only affects this rendering (currently the
 * event timestamp). Re-rendering the same entry under another locale yields
 * that locale's text without touching storage.
 */
public final class AuditLogFormatter {

    private AuditLogFormatter() {
    }

    /**
     * Render {@code entry} for {@code locale}; a {@code null} locale
     * falls back to US English.
     *
     * @throws IllegalArgumentException when {@code entry} is {@code null}
     */
    public static String format(AuditEntry entry, Locale locale) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        Locale effective = locale == null ? Locale.US : locale;
        DateTimeFormatter timestamps = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(effective)
                .withZone(ZoneId.of("UTC"));
        String when = timestamps.format(entry.timestamp());
        String actor = entry.actor() == null ? "-" : entry.actor().toString();
        String land = entry.landId() == null ? "-" : entry.landId().value().toString();
        String world = entry.worldId() == null ? "-" : entry.worldId().toString();
        return "[" + when + "] " + entry.action() + " actor=" + actor + " land=" + land + " world=" + world;
    }

    /** Localization seam for the fallback locale used when none is supplied. */
    static Locale defaultLocale() {
        return Locale.US;
    }
}
