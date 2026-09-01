package com.smile.chunkland.config;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable typed view of {@code config.yml::messages}.
 *
 * <p>Both fields have explicit defaults ({@code en_US} / {@code 2}) when the
 * section is absent. Validation is strict when the section is present.</p>
 */
public record MessageSettings(Locale defaultLocale, int cooldownSeconds) {

    public static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("en-US");
    public static final int DEFAULT_COOLDOWN_SECONDS = 2;

    public MessageSettings {
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        if (defaultLocale.getLanguage().isEmpty()) {
            throw new IllegalArgumentException("defaultLocale must have a language: " + defaultLocale);
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds must be >= 0: " + cooldownSeconds);
        }
    }

    public static MessageSettings defaults() {
        return new MessageSettings(DEFAULT_LOCALE, DEFAULT_COOLDOWN_SECONDS);
    }

    /**
     * Parse a locale tag (accepts both {@code en_US} and {@code en-US}) into a Locale.
     */
    public static Locale parseLocaleTag(String raw, String path) {
        if (raw == null || raw.isBlank()) {
            throw new ConfigValidationException(path + " must be a non-empty locale tag, got '" + raw + "'");
        }
        String normalized = raw.trim().replace('_', '-');
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale == null || locale.getLanguage().isEmpty()) {
            throw new ConfigValidationException(path + " must be a parseable locale tag, got '" + raw + "'");
        }
        return locale;
    }

    /**
     * Format locale as {@code en_US} for config / snapshot comparison.
     */
    public static String formatLocale(Locale locale) {
        if (locale == null) return "";
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (country == null || country.isEmpty()) {
            return lang;
        }
        return lang + "_" + country;
    }
}
