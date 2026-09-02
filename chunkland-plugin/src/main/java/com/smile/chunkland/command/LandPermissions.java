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
    public static final String BAN = "chunkland.command.land.ban";
    public static final String UNBAN = "chunkland.command.land.unban";
    public static final String EXPAND = "chunkland.command.land.expand";
    public static final String RENAME = "chunkland.command.land.rename";
    public static final String DELETE = "chunkland.command.land.delete";

    public static final List<String> ALL_ORDERED = List.of(
            HELP, CONFIRM, WAND, CLAIM, TRUST, UNTRUST, BAN, UNBAN, EXPAND, RENAME, DELETE);

    private static final Map<String, String> BY_SUBCOMMAND = Map.ofEntries(
            Map.entry("help", HELP),
            Map.entry("confirm", CONFIRM),
            Map.entry("wand", WAND),
            Map.entry("claim", CLAIM),
            Map.entry("trust", TRUST),
            Map.entry("untrust", UNTRUST),
            Map.entry("ban", BAN),
            Map.entry("unban", UNBAN),
            Map.entry("expand", EXPAND),
            Map.entry("rename", RENAME),
            Map.entry("delete", DELETE)
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
