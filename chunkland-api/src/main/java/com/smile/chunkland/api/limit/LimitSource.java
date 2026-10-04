package com.smile.chunkland.api.limit;

/**
 * Where a resolved limit value came from.
 *
 * <p>This is surfaced so future {@code /land inspect} style features can
 * explain why a limit has its current value.
 */
public enum LimitSource {
    CONFIG,
    PROVIDER
}
