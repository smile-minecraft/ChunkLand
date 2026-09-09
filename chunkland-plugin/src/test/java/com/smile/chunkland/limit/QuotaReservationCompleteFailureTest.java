package com.smile.chunkland.limit;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.config.ChunkLandConfig;
import com.smile.chunkland.config.LimitSettings;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * A failed {@code complete()} must not wedge the reservation: the handle stays
 * releasable, counters are untouched by the failed transition, and the quota
 * recovers without leaking or over-committing.
 */
class QuotaReservationCompleteFailureTest {

    private static OwnerQuotaService service(int maxLands, int maxChunks) {
        return new OwnerQuotaService(new LimitResolver(
                new ChunkLandConfig(Map.of(), new LimitSettings(maxLands, maxChunks, 128, 16), 0L, Map.of())));
    }

    /** Reflectively rewrite one live counter to simulate a partial-consumption anomaly. */
    private static void setReserved(OwnerQuotaService svc, OwnerRef owner, String field, int value)
            throws Exception {
        Field statesField = OwnerQuotaService.class.getDeclaredField("states");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> states =
                (ConcurrentHashMap<String, Object>) statesField.get(svc);
        Object state = states.get(owner.key());
        assertNotNull(state, "reservation state must exist");
        Field counter = state.getClass().getDeclaredField(field);
        counter.setAccessible(true);
        counter.setInt(state, value);
    }

    @Test
    void failedChunkCompleteKeepsReservationReleasable() throws Exception {
        OwnerQuotaService svc = service(5, 10);
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService.QuotaReservation reservation =
                svc.tryReserveChunks(owner, 5).orElseThrow();
        assertEquals(5, svc.chunkReserved(owner));

        // Only 2 of the 5 reserved chunks remain: the transition must refuse
        // without partially committing.
        setReserved(svc, owner, "chunkReserved", 2);
        assertThrows(IllegalStateException.class, reservation::complete);
        assertEquals(0, svc.chunkCommitted(owner), "failed complete must not partially commit");
        assertEquals(2, svc.chunkReserved(owner), "failed complete must not consume the remainder");

        // The same handle must still release what is left; quota recovers fully.
        reservation.release();
        assertEquals(0, svc.chunkReserved(owner), "failed reservation must stay releasable");
        assertEquals(0, svc.chunkCommitted(owner));
        assertTrue(svc.tryReserveChunks(owner, 5).isPresent(), "quota must recover after release");
    }

    @Test
    void failedLandCompleteKeepsReservationReleasable() throws Exception {
        OwnerQuotaService svc = service(5, 256);
        OwnerRef owner = OwnerRef.player(UUID.randomUUID());
        OwnerQuotaService.QuotaReservation reservation =
                svc.tryReserveLand(owner).orElseThrow();
        assertEquals(1, svc.landReserved(owner));

        // The reservation row vanished underneath the live handle.
        setReserved(svc, owner, "landReserved", 0);
        assertThrows(IllegalStateException.class, reservation::complete);
        assertEquals(0, svc.landCommitted(owner), "failed complete must not partially commit");

        // Release stays safe and the owner can reserve again: no wedged slot.
        reservation.release();
        assertEquals(0, svc.landReserved(owner));
        assertTrue(svc.tryReserveLand(owner).isPresent(), "quota must recover after release");
        assertEquals(1, svc.landReserved(owner));
    }
}
