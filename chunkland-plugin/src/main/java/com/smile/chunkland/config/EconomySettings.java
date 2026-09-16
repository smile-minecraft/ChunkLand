package com.smile.chunkland.config;

import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.PricingTable;
import com.smile.chunkland.api.money.PricingTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Typed view of {@code config.yml::economy}: the claim currency plus the
 * owner-total price-per-chunk tiers.
 *
 * <p>Minimal shape for the Red phase: the parser that builds it from YAML
 * has not landed yet, so every snapshot still reports no economy.
 */
public final class EconomySettings {

    private final Currency currency;
    private final PricingTable pricing;

    public EconomySettings(Currency currency, PricingTable pricing) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        if (!pricing.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "pricing currency " + pricing.currency() + " must match " + currency);
        }
    }

    public Currency currency() {
        return currency;
    }

    public PricingTable pricing() {
        return pricing;
    }

    private static boolean tiersEqual(List<PricingTier> a, List<PricingTier> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            PricingTier x = a.get(i);
            PricingTier y = b.get(i);
            if (x.until() != y.until()
                    || x.price().minorUnits() != y.price().minorUnits()
                    || !x.price().currency().equals(y.price().currency())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EconomySettings other)) {
            return false;
        }
        return currency.equals(other.currency)
                && tiersEqual(
                        new ArrayList<>(pricing.tiers()), new ArrayList<>(other.pricing.tiers()));
    }

    @Override
    public int hashCode() {
        int tiers = 1;
        for (PricingTier tier : pricing.tiers()) {
            tiers = 31 * tiers
                    + Long.hashCode(tier.until())
                    + Long.hashCode(tier.price().minorUnits());
        }
        return 31 * currency.hashCode() + tiers;
    }

    @Override
    public String toString() {
        return "EconomySettings[currency=" + currency + ", tiers=" + pricing.tiers().size() + "]";
    }
}
