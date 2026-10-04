package com.smile.chunkland.protection;

import com.smile.chunkland.api.land.LandId;
import com.smile.chunkland.runtime.index.LandRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Snapshot-backed {@link EntryProtectionAdapter.BanLookup} for live
 * enforcement.
 *
 * <p>Every call reads the already-published immutable land index and the
 * already-published immutable ENTRY ban set, then answers from those two
 * memory-only reads: no SQL, no Bukkit, no chunk load, no network. A known
 * land the player is not banned on answers {@code false}; wilderness (no
 * owning land) also answers {@code false}. An unloaded ban snapshot — cache
 * startup before the first durable load, or a failed reload — answers empty
 * on known lands so the adapter fails closed instead of trusting a
 * known-unbanned read. Push-out candidates on those lands are excluded with
 * it, so the deny stands as cancel-only until the snapshot loads; that is
 * the intended freeze, not a wedge — movement itself is denied while the
 * ban state is unverifiable. Anything else unverifiable — a missing supplier, a
 * missing snapshot, an unknown lookup failure — answers empty so the
 * adapter fails closed.
 */
public final class EntryBanLookup implements EntryProtectionAdapter.BanLookup {

    private final Supplier<LandRegistry> registries;
    private final Supplier<LandAuthorisationSnapshot> bans;

    /**
     * @param registries live land index source (typically a volatile read);
     *                   {@code null} or failing reads fail closed
     * @param bans live ENTRY ban source (typically a volatile read);
     *             {@code null} or failing reads fail closed
     */
    public EntryBanLookup(Supplier<LandRegistry> registries,
            Supplier<LandAuthorisationSnapshot> bans) {
        this.registries = registries;
        this.bans = bans;
    }

    @Override
    public Optional<Boolean> bannedAt(UUID playerId, UUID worldId, int chunkX, int chunkZ) {
        try {
            Supplier<LandRegistry> registries = this.registries;
            Supplier<LandAuthorisationSnapshot> bans = this.bans;
            if (playerId == null || worldId == null || registries == null || bans == null) {
                return Optional.empty();
            }
            LandRegistry snapshot;
            LandAuthorisationSnapshot auth;
            try {
                snapshot = registries.get();
                auth = bans.get();
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
            if (snapshot == null || auth == null) {
                return Optional.empty();
            }
            LandId landId;
            try {
                landId = snapshot.findLandId(worldId, chunkX, chunkZ);
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
            if (landId == null) {
                return Optional.of(false);
            }
            if (!auth.loaded()) {
                return Optional.empty();
            }
            try {
                return Optional.of(auth.isBanned(playerId, landId));
            } catch (RuntimeException unresolved) {
                return Optional.empty();
            }
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }
}
