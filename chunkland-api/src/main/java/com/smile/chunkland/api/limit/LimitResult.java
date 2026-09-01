package com.smile.chunkland.api.limit;

import java.util.Objects;

/**
 * Immutable resolved limit with its provenance.
 *
 * <p>Both fields are validated: {@code limit} must be non-negative, {@code source}
 * must be non-null. The value is expressed as a plain integer / long depending on
 * the {@link LimitType} caller; the resolver is responsible for returning a
 * consistent numeric range. This object is immutable and thread-safe.</p>
 */
public final class LimitResult {

    private final long limit;
    private final LimitSource source;

    public LimitResult(long limit, LimitSource source) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative: " + limit);
        }
        this.limit = limit;
        this.source = Objects.requireNonNull(source, "source");
    }

    public static LimitResult of(long limit, LimitSource source) {
        return new LimitResult(limit, source);
    }

    public long limit() {
        return limit;
    }

    public LimitSource source() {
        return source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LimitResult other)) return false;
        return limit == other.limit && source == other.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(limit, source);
    }

    @Override
    public String toString() {
        return "LimitResult{limit=" + limit + ", source=" + source + '}';
    }
}
