package com.smile.chunkland.api.money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, validated pricing table built from the unified tier model.
 *
 * <p>The pricing basis is the owner's global total chunk count across all Player Lands, NOT the
 * current transaction's chunk count and NOT a single Land's size. Server Land never counts toward
 * the basis and is never charged (the caller is responsible for excluding it before invoking these
 * methods). Given that basis, the marginal price of the next chunk — and the total price of a claim
 * lot — is a deterministic function of the tier boundaries.
 *
 * <p>Construction enforces the tier contract: the list must be non-empty, every finite {@code until}
 * is {@code >= 1}, tiers are strictly increasing (no overlap, no gap), all prices share one currency,
 * prices are non-negative, and the final tier is the unbounded top tier (no gap at infinity). The
 * resulting table is immutable and its pricing is order-independent and reproducible.
 */
public final class PricingTable {

    private final List<PricingTier> tiers;
    private final Currency currency;

    private PricingTable(List<PricingTier> tiers, Currency currency) {
        this.tiers = List.copyOf(tiers);
        this.currency = currency;
    }

    /**
     * Build and validate a pricing table from ordered tiers.
     *
     * @throws IllegalArgumentException if the tiers violate the contiguous, non-overlapping,
     *                                  single-currency, non-negative, unbounded-top contract.
     */
    public static PricingTable of(List<PricingTier> tiers) {
        Objects.requireNonNull(tiers, "tiers");
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("tiers must not be empty");
        }
        Currency currency = null;
        Long prevUntil = null;
        boolean seenUnbounded = false;
        for (int i = 0; i < tiers.size(); i++) {
            PricingTier t = tiers.get(i);
            if (currency == null) {
                currency = t.price().currency();
            } else if (!currency.equals(t.price().currency())) {
                throw new IllegalArgumentException(
                        "all tier prices must share currency " + currency + ", found " + t.price().currency());
            }
            if (t.isUnbounded()) {
                if (i != tiers.size() - 1) {
                    throw new IllegalArgumentException("unbounded tier must be the last tier");
                }
                seenUnbounded = true;
            } else {
                if (t.until() < 1) {
                    throw new IllegalArgumentException("finite until must be >= 1, got " + t.until());
                }
                if (prevUntil != null && t.until() <= prevUntil) {
                    throw new IllegalArgumentException(
                            "tiers must be strictly increasing (overlap/gap): previous until " + prevUntil
                                    + " >= " + t.until());
                }
                prevUntil = t.until();
            }
        }
        if (!seenUnbounded) {
            throw new IllegalArgumentException("tiers must end with an unbounded tier (no gap at top)");
        }
        return new PricingTable(new ArrayList<>(tiers), currency);
    }

    /** The immutable tier list. */
    public List<PricingTier> tiers() {
        return tiers;
    }

    public Currency currency() {
        return currency;
    }

    /**
     * Marginal price of the next single chunk given the owner's current global total chunk count.
     *
     * <p>The next chunk is global index {@code ownerTotalChunks + 1}; its price is the tier that
     * covers that index. This is the canonical pricing basis and must not be fed the
     * transaction count.
     */
    public Money marginalPrice(long ownerTotalChunks) {
        if (ownerTotalChunks < 0) {
            throw new IllegalArgumentException("ownerTotalChunks must be >= 0, got " + ownerTotalChunks);
        }
        long nextChunkIndex = Math.addExact(ownerTotalChunks, 1L);
        return priceForChunkIndex(nextChunkIndex);
    }

    /**
     * Total exact price of claiming {@code additionalChunks} more chunks, given the owner's current
     * global total chunk count as the basis.
     *
     * <p>Each new chunk's global index is {@code ownerTotalChunks + k} ({@code k = 1..additionalChunks});
     * its tier price is summed. The result is exact (no rounding loss) and deterministic. The basis is
     * the owner's global total, so the same lot priced from different transaction sizes but the same
     * global total yields the same total.
     */
    public Money priceForClaim(long ownerTotalChunks, long additionalChunks) {
        if (ownerTotalChunks < 0) {
            throw new IllegalArgumentException("ownerTotalChunks must be >= 0, got " + ownerTotalChunks);
        }
        if (additionalChunks <= 0) {
            throw new IllegalArgumentException("additionalChunks must be > 0, got " + additionalChunks);
        }
        long from = Math.addExact(ownerTotalChunks, 1L);
        long to = Math.addExact(ownerTotalChunks, additionalChunks);
        Money total = Money.zero(currency);
        long prevUntil = 0L;
        for (PricingTier t : tiers) {
            // No chunk index can exceed Long.MAX_VALUE, so once a finite tier reaches it the
            // remaining tiers (including an unbounded top tier) are empty. Guarding here also
            // avoids the `prevUntil + 1` wraparound that would otherwise re-count chunks.
            if (prevUntil == Long.MAX_VALUE) {
                break;
            }
            long tierHi = t.isUnbounded() ? Long.MAX_VALUE : t.until();
            long lo = Math.max(from, prevUntil + 1L);
            long hi = Math.min(to, tierHi);
            if (lo <= hi) {
                long count = hi - lo + 1L;
                total = total.add(t.price().multiply(count));
            }
            if (t.isUnbounded()) {
                break;
            }
            prevUntil = t.until();
        }
        return total;
    }

    private Money priceForChunkIndex(long chunkIndex) {
        for (PricingTier t : tiers) {
            if (t.covers(chunkIndex)) {
                return t.price();
            }
        }
        throw new IllegalStateException("no tier covers chunk index " + chunkIndex);
    }
}
