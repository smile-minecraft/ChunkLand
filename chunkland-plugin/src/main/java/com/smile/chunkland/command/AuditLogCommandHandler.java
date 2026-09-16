package com.smile.chunkland.command;

import com.smile.chunkland.persistence.AuditEntry;
import com.smile.chunkland.persistence.AuditRepository;
import com.smile.chunkland.persistence.AuditSearchQuery;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Read-only {@code /land log} handler over the durable audit history.
 *
 * <p>The tail after {@code log} is parsed by {@link AuditLogQueryParser}
 * ({@code u:/t:/a:/land:/world:} plus {@code limit:/page:}); the time
 * filter resolves against the injected clock so tests pin it without
 * sleeping. The query runs on the persistence executor through
 * {@link AuditRepository#search} and each row renders at reply time with
 * the sender's current locale via {@link AuditLogFormatter} — storage
 * never carries a locale.
 *
 * <p>Every degraded state fails closed: blank or malformed filters reply
 * usage, a missing repository or clock replies unavailable, and query
 * failures reply failed. Replies never escape the terminal callback.
 */
public final class AuditLogCommandHandler implements LandCommand.Handler {

    private final Supplier<AuditRepository> audits;
    private final Supplier<Instant> clock;

    /**
     * @param audits audit read source; {@code null} or failing reads fail closed
     * @param clock  time source for {@code t:} filters; {@code null} or failing reads fail closed
     */
    public AuditLogCommandHandler(Supplier<AuditRepository> audits, Supplier<Instant> clock) {
        this.audits = audits;
        this.clock = clock;
    }

    @Override
    public void handle(CommandSender sender, String[] args, ReplySink sink) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(sink, "sink");
        String raw = joinTail(args, 1);
        if (raw == null) {
            sink.reply("command.land.log.usage", Map.of());
            return;
        }
        AuditRepository repo = readRepo();
        if (repo == null) {
            sink.reply("command.land.log.failed", Map.of("reason", "log.unavailable"));
            return;
        }
        Instant now = readClock();
        if (now == null) {
            sink.reply("command.land.log.failed", Map.of("reason", "log.unavailable"));
            return;
        }
        AuditSearchQuery query;
        try {
            query = AuditLogQueryParser.parse(raw, now);
        } catch (IllegalArgumentException badFilter) {
            sink.reply("command.land.log.usage", Map.of());
            return;
        } catch (RuntimeException failure) {
            sink.reply("command.land.log.failed", Map.of("reason", "log.failed"));
            return;
        }
        CompletionStage<List<AuditEntry>> stage;
        try {
            stage = repo.search(query);
        } catch (RuntimeException failure) {
            sink.reply("command.land.log.failed", Map.of("reason", "log.failed"));
            return;
        }
        if (stage == null) {
            sink.reply("command.land.log.failed", Map.of("reason", "log.failed"));
            return;
        }
        Locale locale = senderLocale(sender);
        stage.whenComplete((rows, failure) -> {
            try {
                if (failure != null || rows == null) {
                    sink.reply("command.land.log.failed", Map.of("reason", "log.failed"));
                } else if (rows.isEmpty()) {
                    sink.reply("command.land.log.empty", Map.of());
                } else {
                    for (AuditEntry row : rows) {
                        if (row == null) {
                            continue;
                        }
                        sink.reply("command.land.log.line",
                                Map.of("value", AuditLogFormatter.format(row, locale)), locale);
                    }
                }
            } catch (RuntimeException replyFailure) {
                // Terminal reply path: never let a sink failure escape.
            }
        });
    }

    private AuditRepository readRepo() {
        try {
            return audits == null ? null : audits.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private Instant readClock() {
        try {
            return clock == null ? null : clock.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static Locale senderLocale(CommandSender sender) {
        if (sender instanceof Player player) {
            try {
                return player.locale();
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        return null;
    }

    private static String joinTail(String[] args, int from) {
        if (args == null || args.length <= from) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(token.strip());
        }
        if (joined.isEmpty()) {
            return null;
        }
        return joined.toString();
    }
}
