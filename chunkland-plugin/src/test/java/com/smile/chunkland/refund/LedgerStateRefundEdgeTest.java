package com.smile.chunkland.refund;

import static org.junit.jupiter.api.Assertions.*;

import com.smile.chunkland.persistence.LedgerState;
import org.junit.jupiter.api.Test;

/**
 * Refund ledger edges: a domain-first refund commits the domain while the
 * ledger is {@code DOMAIN_COMMITTED} and only then moves money. A failed
 * deposit must park the row in {@code COMPENSATION_PENDING} (shared with the
 * existing recovery retry contract) and a confirmed deposit must settle it as
 * {@code COMPENSATED}, both directly from {@code DOMAIN_COMMITTED}.
 */
class LedgerStateRefundEdgeTest {

    @Test
    void domainCommittedCanParkForCompensation() {
        assertTrue(LedgerState.DOMAIN_COMMITTED.canTransitionTo(LedgerState.COMPENSATION_PENDING));
    }

    @Test
    void domainCommittedCanSettleCompensated() {
        assertTrue(LedgerState.DOMAIN_COMMITTED.canTransitionTo(LedgerState.COMPENSATED));
    }

    @Test
    void createdCanCommitDomainDirectlyForRefunds() {
        assertTrue(LedgerState.CREATED.canTransitionTo(LedgerState.DOMAIN_COMMITTED));
        assertTrue(LedgerState.CREATED.canTransitionTo(LedgerState.NEEDS_RECONCILIATION));
    }

    @Test
    void claimEdgesStayUnchanged() {
        assertTrue(LedgerState.DOMAIN_COMMITTED.canTransitionTo(LedgerState.ACTIVE));
        assertTrue(LedgerState.CHARGED.canTransitionTo(LedgerState.COMPENSATION_PENDING));
        assertTrue(LedgerState.COMPENSATION_PENDING.canTransitionTo(LedgerState.COMPENSATED));
        assertFalse(LedgerState.ACTIVE.canTransitionTo(LedgerState.FAILED));
    }
}
