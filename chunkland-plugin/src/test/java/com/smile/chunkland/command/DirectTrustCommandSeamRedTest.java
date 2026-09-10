package com.smile.chunkland.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.protection.ManagementPermissionGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Red: direct trust / land default seams must stop answering {@code not_yet}.
 *
 * <p>{@code /land trust}, {@code /land untrust} and the new
 * {@code /land default} entry carry durable direct-trust semantics, so the
 * production stub map must no longer answer them with
 * {@code command.land.not_yet}, and the {@code default} subcommand must be
 * registered with its Bukkit node and its {@code MANAGE_PERMISSION} gate.
 */
class DirectTrustCommandSeamRedTest {

    private static List<String> runStub(String subcommand) {
        List<String> keys = new ArrayList<>();
        LandCommand.Handler handler = LandCommand.defaultStubHandlers().get(subcommand);
        assertNotNull(handler, subcommand + " must stay registered");
        ReplySink sink = new ReplySink() {
            @Override
            public void reply(String messageKey, Map<String, Object> vars) {
                keys.add(messageKey);
            }

            @Override
            public void reply(String messageKey, Map<String, Object> vars, Locale localeOverride) {
                keys.add(messageKey);
            }
        };
        handler.handle(stubSender(), new String[] {subcommand}, sink);
        return keys;
    }

    private static CommandSender stubSender() {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    @Test
    void trustStubMustStopAnsweringNotYet() {
        assertNotEquals(List.of("command.land.not_yet"), runStub("trust"),
                "/land trust must carry durable direct-trust semantics, not the not_yet stub");
    }

    @Test
    void untrustStubMustStopAnsweringNotYet() {
        assertNotEquals(List.of("command.land.not_yet"), runStub("untrust"),
                "/land untrust must carry durable direct-trust semantics, not the not_yet stub");
    }

    @Test
    void defaultSubcommandIsRegisteredWithNodeAndGate() {
        assertTrue(LandCommand.SUBCOMMANDS.contains("default"),
                "/land default must be a registered subcommand");
        assertNotNull(LandPermissions.forSubcommand("default"),
                "/land default must have an explicit Bukkit permission node");
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                LandCommand.managementActionFor("default"),
                "/land default must run through the MANAGE_PERMISSION gate");
        assertEquals(Optional.of(ProtectionActionType.MANAGE_PERMISSION),
                ManagementPermissionGate.actionForSubcommand("default"),
                "domain gate must map default to MANAGE_PERMISSION");
    }
}
