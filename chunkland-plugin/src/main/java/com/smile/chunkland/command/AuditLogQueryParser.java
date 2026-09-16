package com.smile.chunkland.command;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.persistence.AuditSearchQuery;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Parses the {@code /land log} filter syntax into an {@link AuditSearchQuery}.
 *
 * <p>Supported tokens (whitespace-separated, prefix case-insensitive):
 * {@code u:<player-uuid>}, {@code t:<duration>} (e.g. {@code 30s},
 * {@code 15m}, {@code 24h}, {@code 7d}), {@code a:<action>} (with the
 * {@code depth} shorthand for {@code DEPTH_EXTEND}), {@code land:<land-uuid>},
 * {@code world:<world-uuid>}, plus {@code limit:<n>} and {@code page:<n>} for
 * paging. Blank input yields the default first page. Anything else fails
 * closed with {@link IllegalArgumentException}; the repository layer is never
 * reached with a half-parsed filter.
 *
 * <p>The parser is a pure function of its inputs: it stores nothing and
 * resolves the time filter against the supplied {@code now}, so tests can
 * pin it without sleeping.
 */
public final class AuditLogQueryParser {

    /** Default page size when no {@code limit:} token is given. */
    public static final int DEFAULT_LIMIT = 20;

    private AuditLogQueryParser() {
    }

    /**
     * Parse {@code rawArgs} against {@code now}.
     *
     * @throws IllegalArgumentException on unknown prefixes, malformed UUIDs,
     *                                  durations, paging, or blank values
     */
    public static AuditSearchQuery parse(String rawArgs, Instant now) {
        Objects.requireNonNull(now, "now");
        if (rawArgs == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        UUID actor = null;
        Instant since = null;
        String action = null;
        LandId landId = null;
        UUID worldId = null;
        int limit = DEFAULT_LIMIT;
        int page = 1;
        String trimmed = rawArgs.trim();
        if (!trimmed.isEmpty()) {
            for (String token : trimmed.split("\\s+")) {
                int colon = token.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("unrecognized filter token '" + token + "'");
                }
                String prefix = token.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = token.substring(colon + 1);
                switch (prefix) {
                    case "u" -> actor = parseUuid(value, token, "player");
                    case "t" -> since = parseSince(value, token, now);
                    case "a" -> action = parseAction(value, token);
                    case "land" -> landId = new LandId(parseUuid(value, token, "land"));
                    case "world" -> worldId = parseUuid(value, token, "world");
                    case "limit" -> limit = parsePositiveInt(value, token, "limit");
                    case "page" -> page = parsePositiveInt(value, token, "page");
                    default -> throw new IllegalArgumentException("unknown filter '" + prefix + "' in '" + token + "'");
                }
            }
        }
        int offset = Math.multiplyExact(page - 1, limit);
        return new AuditSearchQuery(actor, since, action, landId, worldId, limit, offset);
    }

    private static UUID parseUuid(String value, String token, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty " + kind + " uuid in '" + token + "'");
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException(
                    "invalid " + kind + " uuid in '" + token + "'", bad);
        }
    }

    private static Instant parseSince(String value, String token, Instant now) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty time filter in '" + token + "'");
        }
        String text = value.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith("-") || text.startsWith("+")) {
            throw new IllegalArgumentException("time filter must be a positive duration in '" + token + "'");
        }
        if (text.length() < 2) {
            throw new IllegalArgumentException("time filter needs a value and a unit in '" + token + "'");
        }
        char unit = text.charAt(text.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(text.substring(0, text.length() - 1));
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("time filter must be a positive duration in '" + token + "'", bad);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("time filter must be a positive duration in '" + token + "'");
        }
        long seconds = switch (unit) {
            case 's' -> amount;
            case 'm' -> Math.multiplyExact(amount, 60L);
            case 'h' -> Math.multiplyExact(amount, 3_600L);
            case 'd' -> Math.multiplyExact(amount, 86_400L);
            default -> throw new IllegalArgumentException(
                    "time filter unit must be one of s/m/h/d in '" + token + "'");
        };
        return now.minusSeconds(seconds);
    }

    private static String parseAction(String value, String token) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty action filter in '" + token + "'");
        }
        String text = value.strip();
        if (text.equalsIgnoreCase("depth")) {
            return "DEPTH_EXTEND";
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private static int parsePositiveInt(String value, String token, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("empty " + kind + " in '" + token + "'");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException(
                    "invalid " + kind + " value in '" + token + "'", bad);
        }
        if (parsed < 1 || parsed > AuditSearchQuery.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    kind + " must be in [1, " + AuditSearchQuery.MAX_LIMIT + "] in '" + token + "'");
        }
        return parsed;
    }
}
