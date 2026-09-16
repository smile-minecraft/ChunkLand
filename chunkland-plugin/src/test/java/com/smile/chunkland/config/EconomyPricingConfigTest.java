package com.smile.chunkland.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.money.Currency;
import org.junit.jupiter.api.Test;

/**
 * Red: {@code config.yml} gains a typed {@code economy} section (currency +
 * price-per-chunk tiers). Absent stays backward-compatible (null economy);
 * anything missing, illegal, non-contiguous, or overflowing fails closed at
 * load with no zero/unbounded fallback.
 */
class EconomyPricingConfigTest {

    private static String validTiers() {
        return """
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """;
    }

    @Test
    void validEconomyParsesToCurrencyAndTiers() {
        ChunkLandConfig config = ConfigSchema.parseYamlText(validTiers());

        EconomySettings economy = config.economy();
        assertTrue(economy != null);
        assertEquals(Currency.of("EMC", 2), economy.currency());
        // 25 fresh chunks: 20 x 1.00 + 5 x 2.00 = 30.00 EMC = 3000 minor.
        assertEquals(3000L, economy.pricing().priceForClaim(0, 25).minorUnits());
        // Owner-total basis: the 21st chunk prices at the second tier.
        assertEquals(200L, economy.pricing().marginalPrice(20).minorUnits());
    }

    @Test
    void missingEconomyStaysBackwardCompatibleWithNull() {
        ChunkLandConfig config = ConfigSchema.parseYamlText("limits:\n  max-lands-per-player: 5\n");

        assertNull(config.economy());
    }

    @Test
    void nullEconomyFailsClosed() {
        assertThrows(ConfigValidationException.class,
                () -> ConfigSchema.parseYamlText("economy:\n"));
    }

    @Test
    void missingCurrencyFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: 1.00
                """));
    }

    @Test
    void blankCurrencyCodeFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: "  "
                    scale: 2
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: 1.00
                """));
    }

    @Test
    void currencyScaleOutOfRangeFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 19
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: 1.00
                """));
    }

    @Test
    void missingTiersFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing: {}
                """));
    }

    @Test
    void overlappingTiersFailClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                      - until: 20
                        price-per-chunk: 2.00
                      - until: unbounded
                        price-per-chunk: 3.00
                """));
    }

    @Test
    void finiteTopTierLeavesGapAtInfinity() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 20
                        price-per-chunk: 1.00
                """));
    }

    @Test
    void negativePriceFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: -1.00
                """));
    }

    @Test
    void nonFinitePriceFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: .nan
                """));
    }

    @Test
    void overflowingPriceFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: 99999999999999999999.00
                """));
    }

    @Test
    void zeroUntilFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: 0
                        price-per-chunk: 1.00
                      - until: unbounded
                        price-per-chunk: 2.00
                """));
    }

    @Test
    void unknownEconomyKeyFailsClosed() {
        assertThrows(ConfigValidationException.class, () -> ConfigSchema.parseYamlText("""
                economy:
                  currency:
                    code: EMC
                    scale: 2
                  pricing:
                    tiers:
                      - until: unbounded
                        price-per-chunk: 1.00
                        bogus: 1
                """));
    }

    @Test
    void economySurvivesReloadEpochBump() {
        ChunkLandConfig config = ConfigSchema.parseYamlText(validTiers());
        ChunkLandConfig bumped = config.withEpochsBumped(
                config.worlds(), config.limits(), config.messages(), config.selection(),
                config.subjectDefaults(), config.ruleDefaults(), config.decisionCacheMaxEntries());

        assertTrue(bumped.economy() != null);
        assertEquals(config.economy(), bumped.economy());
    }
}
