package com.smile.chunkland.command;

import java.util.List;
import java.util.Map;

/**
 * Permission registry for {@code /land} subcommands.
 *
 * <p>Every registered subcommand has an explicit node; there is no implicit
 * top-level gate. All nodes default to {@code op} in {@code plugin.yml}.</p>
 */
public final class LandPermissions {

    public static final String HELP = "chunkland.command.land.help";
    public static final String CONFIRM = "chunkland.command.land.confirm";
    public static final String WAND = "chunkland.command.land.wand";
    public static final String CLAIM = "chunkland.command.land.claim";
    public static final String TRUST = "chunkland.command.land.trust";
    public static final String UNTRUST = "chunkland.command.land.untrust";
    public static final String DEFAULT = "chunkland.command.land.default";
    public static final String BAN = "chunkland.command.land.ban";
    public static final String UNBAN = "chunkland.command.land.unban";
    public static final String EXPAND = "chunkland.command.land.expand";
    public static final String SHRINK = "chunkland.command.land.shrink";
    public static final String SUBLAND = "chunkland.command.land.subland";
    public static final String RENAME = "chunkland.command.land.rename";
    public static final String DELETE = "chunkland.command.land.delete";
    public static final String GROUP = "chunkland.command.land.group";
    public static final String PROFILE = "chunkland.command.land.profile";
    public static final String BINDING = "chunkland.command.land.binding";
    public static final String EXPLAIN = "chunkland.command.land.explain";
    public static final String INSPECT = "chunkland.command.land.inspect";
    public static final String LOG = "chunkland.command.land.log";
    public static final String HISTORY = "chunkland.command.land.history";
    public static final String MANAGE = "chunkland.command.land.manage";
    /**
     * Independent ledger administration node for {@code /land admin ledger}.
     * Deliberately separate from {@code chunkland.admin.serverland} and the
     * bypass node: holding those never grants ledger verdicts.
     */
    public static final String ADMIN = "chunkland.admin.ledger";
    /**
     * Independent orphan-world administration node for
     * {@code /land admin orphan}. Deliberately separate from the ledger node,
     * {@code chunkland.admin.serverland} and the bypass node: holding those
     * never grants irreversible orphan purges.
     */
    public static final String ORPHAN = "chunkland.admin.orphan";

    /**
     * Independent bypass-toggle authority for {@code /land bypass on|off}.
     * Holding this node only allows <em>attempting</em> the switch; it never
     * authorises a management mutation on its own. Only an explicit audited
     * toggle flips the per-enable bypass memory the domain gate reads.
     * Deliberately separate from {@code chunkland.admin.serverland} and the
     * ledger/orphan nodes: holding those never grants bypass, and holding
     * this never grants steward, ledger or orphan verdicts.
     */
    public static final String BYPASS = "chunkland.admin.bypass";

    public static final List<String> ALL_ORDERED = List.of(
            HELP, CONFIRM, WAND, CLAIM, TRUST, UNTRUST, DEFAULT, BAN, UNBAN, SUBLAND, EXPAND, SHRINK, RENAME, DELETE, GROUP, PROFILE, BINDING, EXPLAIN, INSPECT, LOG, HISTORY, MANAGE, ADMIN, ORPHAN, BYPASS);

    private static final Map<String, String> BY_SUBCOMMAND = Map.ofEntries(
            Map.entry("help", HELP),
            Map.entry("confirm", CONFIRM),
            Map.entry("wand", WAND),
            Map.entry("claim", CLAIM),
            Map.entry("trust", TRUST),
            Map.entry("untrust", UNTRUST),
            Map.entry("default", DEFAULT),
            Map.entry("ban", BAN),
            Map.entry("unban", UNBAN),
            Map.entry("subland", SUBLAND),
            Map.entry("expand", EXPAND),
            Map.entry("shrink", SHRINK),
            Map.entry("unclaim", SHRINK),
            Map.entry("rename", RENAME),
            Map.entry("delete", DELETE),
            Map.entry("group", GROUP),
            Map.entry("profile", PROFILE),
            Map.entry("binding", BINDING),
            Map.entry("explain", EXPLAIN),
            Map.entry("inspect", INSPECT),
            Map.entry("log", LOG),
            Map.entry("history", HISTORY),
            Map.entry("manage", MANAGE),
            Map.entry("bypass", BYPASS),
            Map.entry("admin", ADMIN)
    );

    private LandPermissions() {}

    public static String forSubcommand(String subcommand) {
        if (subcommand == null) return null;
        return BY_SUBCOMMAND.get(subcommand.toLowerCase(java.util.Locale.ROOT));
    }

    public static Map<String, String> bySubcommand() {
        return BY_SUBCOMMAND;
    }
}
