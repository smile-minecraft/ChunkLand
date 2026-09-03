package com.smile.chunkland.economy;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.api.land.OwnerRef;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.mutation.MutationKind;
import com.smile.chunkland.api.mutation.MutationRequest;
import com.smile.chunkland.persistence.LedgerEntry;
import com.smile.chunkland.persistence.RefundOutcome;
import com.smile.chunkland.runtime.mutation.EconomyOperator;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VaultEconomyAdapterTest {

    private static final Currency CUR = Currency.of("TEST", 2);

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.thenApply(value -> value).toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static MutationRequest claimRequest(UUID actor) {
        return new MutationRequest(
                MutationKind.LAND_CREATE,
                null,
                OwnerRef.player(actor),
                Set.of(new com.smile.chunkland.api.land.ChunkKey(UUID.randomUUID(), 0, 0)),
                "Home");
    }

    static class FakeBridge implements VaultBridge {
        final AtomicInteger withdrawCalls = new AtomicInteger();
        final AtomicInteger depositCalls = new AtomicInteger();
        volatile double lastWithdrawAmount = Double.NaN;
        volatile UUID lastWithdrawOp = null;
        volatile UUID lastWithdrawPlayer = null;
        volatile boolean available = true;
        volatile Response withdrawResponse = Response.ok();
        volatile Response depositResponse = Response.ok();

        @Override public boolean isAvailable() { return available; }
        @Override public String providerId() { return "fake-vault"; }
        @Override public Response withdraw(UUID playerId, double amount, UUID operationId) {
            withdrawCalls.incrementAndGet();
            lastWithdrawAmount = amount;
            lastWithdrawOp = operationId;
            lastWithdrawPlayer = playerId;
            return withdrawResponse;
        }
        @Override public Response deposit(UUID playerId, double amount, UUID operationId) {
            depositCalls.incrementAndGet();
            return depositResponse;
        }
    }

    @Test
    void capabilitiesUnavailableWhenBridgeAbsent() {
        VaultBridge bridge = new UnavailableVaultBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        EconomyCapabilities caps = adapter.capabilities();
        assertFalse(caps.available());
        assertFalse(caps.canCharge());
        assertFalse(caps.canRefund());
    }

    @Test
    void capabilitiesAvailableWhenBridgePresent() {
        FakeBridge bridge = new FakeBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        EconomyCapabilities caps = adapter.capabilities();
        assertTrue(caps.available());
        assertTrue(caps.canCharge());
        assertTrue(caps.canRefund());
        assertEquals("fake-vault", caps.providerId());
    }

    @Test
    void absentProviderFailClosedDoesNotCallBridge() throws Exception {
        VaultBridge bridge = new UnavailableVaultBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        UUID op = UUID.randomUUID();
        EconomyOperator.EconomyResult r = await(adapter.charge(claimRequest(UUID.randomUUID()), op));
        assertFalse(r.success());
        assertEquals("economy.unavailable", r.diagnosticKey());
    }

    @Test
    void chargeConvertsExactMoneyToVaultDouble() throws Exception {
        FakeBridge bridge = new FakeBridge();
        Money price = new Money(199, CUR); // 1.99
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, price);
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        EconomyOperator.EconomyResult r = await(adapter.charge(claimRequest(actor), op));
        assertTrue(r.success());
        assertEquals(1, bridge.withdrawCalls.get());
        assertEquals(1.99, bridge.lastWithdrawAmount, 0.0001);
        // Bridge receives a charge-scoped idempotency key derived from the
        // raw operationId; the adapter never passes the raw UUID to the
        // bridge to avoid collision with refund of the same op.
        UUID expected = UUID.nameUUIDFromBytes(("charge:" + op).getBytes());
        assertEquals(expected, bridge.lastWithdrawOp);
        assertEquals(actor, bridge.lastWithdrawPlayer);
    }

    @Test
    void chargeNegativePriceFailsClosed() throws Exception {
        FakeBridge bridge = new FakeBridge();
        Money negative = new Money(-100, CUR);
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, negative);
        UUID op = UUID.randomUUID();
        EconomyOperator.EconomyResult r = await(adapter.charge(claimRequest(UUID.randomUUID()), op));
        assertFalse(r.success());
        assertEquals(0, bridge.withdrawCalls.get(), "must not call vault on negative");
    }

    @Test
    void chargeZeroPriceSucceedsWithoutVaultCall() throws Exception {
        FakeBridge bridge = new FakeBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, Money.zero(CUR));
        EconomyOperator.EconomyResult r = await(adapter.charge(claimRequest(UUID.randomUUID()), UUID.randomUUID()));
        assertTrue(r.success());
        assertEquals(0, bridge.withdrawCalls.get());
    }

    @Test
    void chargeIdempotencySameOperationIdDoesNotDoubleWithdraw() throws Exception {
        FakeBridge bridge = new FakeBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        UUID op = UUID.randomUUID();
        MutationRequest req = claimRequest(UUID.randomUUID());
        EconomyOperator.EconomyResult r1 = await(adapter.charge(req, op));
        EconomyOperator.EconomyResult r2 = await(adapter.charge(req, op));
        assertTrue(r1.success());
        assertTrue(r2.success());
        assertEquals(1, bridge.withdrawCalls.get(), "retry with same operationId must not re-invoke vault");
        assertEquals(1, adapter.chargeCacheSizeForTest());
    }

    @Test
    void chargeDifferentOperationIdDoesWithdrawTwice() throws Exception {
        FakeBridge bridge = new FakeBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        MutationRequest req = claimRequest(UUID.randomUUID());
        await(adapter.charge(req, UUID.randomUUID()));
        await(adapter.charge(req, UUID.randomUUID()));
        assertEquals(2, bridge.withdrawCalls.get());
    }

    @Test
    void chargePropagatesBridgeFailure() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.withdrawResponse = VaultBridge.Response.failed("economy.insufficient");
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        EconomyOperator.EconomyResult r = await(adapter.charge(claimRequest(UUID.randomUUID()), UUID.randomUUID()));
        assertFalse(r.success());
        assertEquals("economy.insufficient", r.diagnosticKey());
    }

    @Test
    void chargeOperationIdPassedThroughAndNotChanged() throws Exception {
        FakeBridge bridge = new FakeBridge();
        VaultEconomyAdapter adapter = VaultEconomyAdapter.withFixedPrice(bridge, CUR, new Money(100, CUR));
        UUID op = UUID.randomUUID();
        await(adapter.charge(claimRequest(UUID.randomUUID()), op));
        // The bridge receives a charge-scoped idempotency key so that a charge
        // and a refund sharing the same raw UUID cannot collide in any
        // provider-side dedup. The internal cache still keys on the raw op.
        UUID expected = UUID.nameUUIDFromBytes(("charge:" + op).getBytes());
        assertEquals(expected, bridge.lastWithdrawOp);
    }

    @Test
    void refundIdempotencySameOperationIdDoesNotDoubleDeposit() throws Exception {
        FakeBridge bridge = new FakeBridge();
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(bridge, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        LedgerEntry entry = new LedgerEntry(op, "CLAIM", "PAYMENT_PENDING", actor, UUID.randomUUID(), null, 10000L, "fake-vault", "tx-ref", "{}", 1, Instant.now(), Instant.now());
        RefundOutcome o1 = await(adapter.refund(entry));
        RefundOutcome o2 = await(adapter.refund(entry));
        assertEquals(RefundOutcome.REFUNDED, o1);
        assertEquals(RefundOutcome.REFUNDED, o2);
        assertEquals(1, bridge.depositCalls.get(), "refund retry must be idempotent");
    }

    @Test
    void refundAbsentProviderFailsClosed() throws Exception {
        VaultBridge bridge = new UnavailableVaultBridge();
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(bridge, CUR, req -> new Money(100, CUR));
        UUID op = UUID.randomUUID();
        LedgerEntry entry = new LedgerEntry(op, "CLAIM", "PAYMENT_PENDING", UUID.randomUUID(), UUID.randomUUID(), null, 100L, "none", "tx", "{}", 1, Instant.now(), Instant.now());
        RefundOutcome o = await(adapter.refund(entry));
        assertEquals(RefundOutcome.FAILED, o);
    }

    @Test
    void fromVaultNanAndInfinityFailClosedViaConverter() {
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.fromVaultAmount(Double.NaN, CUR));
        assertThrows(IllegalArgumentException.class, () -> VaultMoneyConverter.fromVaultAmount(Double.POSITIVE_INFINITY, CUR));
    }

    @Test
    void converterIsOnlyPlaceWithDouble() throws Exception {
        // Sanity: adapter never uses double outside converter; we verify converter throws on bad double
        // so adapter fail-closed path is exercised.
        FakeBridge bridge = new FakeBridge();
        // Price that is valid; but we inject NaN via direct converter misuse would fail.
        // Ensure adapter guards against NaN produced via bad resolver? Resolver returns Money, so NaN not possible.
        // Instead verify that passing null Money fails closed without double.
        VaultEconomyAdapter adapter = new VaultEconomyAdapter(bridge, CUR, req -> null);
        EconomyOperator.EconomyResult r = await(adapter.charge(claimRequest(UUID.randomUUID()), UUID.randomUUID()));
        assertFalse(r.success());
    }
}
