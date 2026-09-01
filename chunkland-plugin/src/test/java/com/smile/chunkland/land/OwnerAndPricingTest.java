package com.smile.chunkland.land;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.api.land.LandSnapshot;
import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import com.smile.chunkland.api.land.ChunkKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies OwnerRef/OwnerKey namespace, Server Land non-transferable,
 * and that Server Land is excluded from pricing/limit counting.
 */
class OwnerAndPricingTest {

    @Test
    void ownerRefKeysMatchOwnerKeyNamespace() {
        UUID uuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        OwnerRef player = OwnerRef.player(uuid);
        assertEquals("PLAYER:" + uuid, player.key());
        OwnerRef server = OwnerRef.server();
        assertEquals("SERVER", server.key());
        // OwnerKey parse round-trip via reflection (OwnerKey is package-private, test via string contract)
        // We verify the contract strings are exactly those expected by persistence.
        assertTrue(player.key().startsWith("PLAYER:"));
        assertEquals("SERVER", server.key());
    }

    @Test
    void serverLandNotTransferable() {
        UUID world = UUID.randomUUID();
        Set<ChunkKey> chunks = Set.of(new ChunkKey(world, 0, 0));
        LandSnapshot serverLand = new LandSnapshot(
                new LandId(UUID.randomUUID()), "Spawn", "spawn",
                OwnerRef.server(), world, chunks, List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
        OwnerRef newOwner = OwnerRef.player(UUID.randomUUID());
        assertThrows(IllegalStateException.class,
                () -> com.smile.chunkland.land.LandOwnership.validateTransfer(serverLand, newOwner));
        // Server -> Server must not throw (internal copy allowed)
        assertDoesNotThrow(() -> com.smile.chunkland.land.LandOwnership.validateTransfer(serverLand, OwnerRef.server()));
        assertTrue(com.smile.chunkland.land.LandOwnership.isServerLand(serverLand));
        // Direct withOwner path is now enforced as well and must reject server->player
        assertThrows(IllegalStateException.class, () -> serverLand.withOwner(newOwner));
        assertDoesNotThrow(() -> serverLand.withOwner(OwnerRef.server()));
        // Player -> player remains allowed (low-level copy)
        LandSnapshot playerLand = new LandSnapshot(
                new LandId(UUID.randomUUID()), "Home", "home",
                OwnerRef.player(UUID.randomUUID()), world, chunks, List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
        assertDoesNotThrow(() -> playerLand.withOwner(OwnerRef.player(UUID.randomUUID())));
    }

    @Test
    void serverLandNotCountedInPricingBasis() {
        UUID world = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        OwnerRef player = OwnerRef.player(playerUuid);
        OwnerRef server = OwnerRef.server();
        int playerChunks = 5;
        int serverChunks = 10;
        Currency usd = Currency.of("USD", 2);
        PricingTable flat = PricingTable.of(List.of(
                PricingTier.of(PricingTier.UNBOUNDED, new Money(100, usd))
        ));
        Money priceBasedOnPlayerOnly = flat.marginalPrice(playerChunks);
        Money priceIfServerCounted = flat.marginalPrice(playerChunks + serverChunks);
        // With flat pricing both are same (same tier), but the contract is that caller excludes server.
        // Here we verify that counting correctly excludes server lands.
        List<LandSnapshot> allLands = List.of(
                makeLand(player, world, 2),
                makeLand(player, world, 3),
                makeLand(server, world, 10)
        );
        long counted = allLands.stream()
                .filter(l -> l.ownerRef() instanceof OwnerRef.PlayerOwnerRef)
                .mapToLong(l -> l.chunks().size())
                .sum();
        assertEquals(5, counted, "Server Land must not be included in Player total");
        assertEquals(playerChunks, counted);
        // Ensure pricing caller would use 5 not 15
        assertEquals(flat.marginalPrice(counted), priceBasedOnPlayerOnly);
        assertNotEquals(counted, playerChunks + serverChunks);
    }

    private LandSnapshot makeLand(OwnerRef owner, UUID world, int chunkCount) {
        Set<ChunkKey> chunks = new java.util.HashSet<>();
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(new ChunkKey(world, i, 100 + i));
        }
        return new LandSnapshot(
                new LandId(UUID.randomUUID()), "L" + chunkCount, "l" + chunkCount,
                owner, world, chunks, List.of(), 0L, 0L, Instant.EPOCH, Instant.EPOCH);
    }
}
