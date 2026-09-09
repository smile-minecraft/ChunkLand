package com.smile.chunkland.persistence;

import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned, self-contained input needed to replay a claim after a restart.
 * Pricing and per-chunk cost basis are values captured at creation time.
 */
public record OperationPayload(
        UUID operationId,
        String operationType,
        UUID actorUuid,
        UUID worldUuid,
        LandId targetLandId,
        List<Chunk> chunkSet,
        long priceMinorUnits,
        String economyProviderId,
        String economyTransactionRef,
        Instant createdAt,
        Instant updatedAt,
        int schemaVersion,
        String landDisplayName,
        Long refundNumerator,
        Long refundDenominator) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OperationPayload(
            UUID operationId,
            String operationType,
            UUID actorUuid,
            UUID worldUuid,
            LandId targetLandId,
            List<Chunk> chunkSet,
            long priceMinorUnits,
            String economyProviderId,
            String economyTransactionRef,
            Instant createdAt,
            Instant updatedAt,
            int schemaVersion) {
        this(operationId, operationType, actorUuid, worldUuid, targetLandId, chunkSet,
                priceMinorUnits, economyProviderId, economyTransactionRef, createdAt,
                updatedAt, schemaVersion, "Recovered land", null, null);
    }

    /**
     * Compatibility overload for rows written before the refund ratio was
     * persisted: legacy payloads carry no ratio and fall back to the
     * amount-implied check in the refund gates.
     */
    public OperationPayload(
            UUID operationId,
            String operationType,
            UUID actorUuid,
            UUID worldUuid,
            LandId targetLandId,
            List<Chunk> chunkSet,
            long priceMinorUnits,
            String economyProviderId,
            String economyTransactionRef,
            Instant createdAt,
            Instant updatedAt,
            int schemaVersion,
            String landDisplayName) {
        this(operationId, operationType, actorUuid, worldUuid, targetLandId, chunkSet,
                priceMinorUnits, economyProviderId, economyTransactionRef, createdAt,
                updatedAt, schemaVersion, landDisplayName, null, null);
    }

    public OperationPayload {
        Objects.requireNonNull(operationId, "operationId");
        if (operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("operationType must not be blank");
        }
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(chunkSet, "chunkSet");
        if (chunkSet.isEmpty()) {
            throw new IllegalArgumentException("chunkSet must not be empty");
        }
        if (priceMinorUnits < 0) {
            throw new IllegalArgumentException("priceMinorUnits must not be negative");
        }
        if (economyProviderId == null || economyProviderId.isBlank()) {
            throw new IllegalArgumentException("economyProviderId must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (createdAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("createdAt must not be after updatedAt");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported payload schema version: " + schemaVersion);
        }
        if (landDisplayName == null || landDisplayName.isBlank()) {
            throw new IllegalArgumentException("landDisplayName must not be blank");
        }
        // Refund ratio is optional so claim rows and legacy refund rows without
        // the fields keep parsing; when present both sides must form a valid
        // 0 <= numerator <= denominator ratio for literal identity checks.
        if ((refundNumerator == null) != (refundDenominator == null)) {
            throw new IllegalArgumentException("refund ratio must carry both numerator and denominator");
        }
        if (refundNumerator != null) {
            if (refundDenominator <= 0) {
                throw new IllegalArgumentException("refundDenominator must be positive");
            }
            if (refundNumerator < 0 || refundNumerator > refundDenominator) {
                throw new IllegalArgumentException("refund ratio must satisfy 0 <= numerator <= denominator");
            }
        }

        Set<ChunkCoordinate> coordinates = new HashSet<>();
        List<Chunk> copy = new ArrayList<>(chunkSet.size());
        for (Chunk chunk : chunkSet) {
            Objects.requireNonNull(chunk, "chunkSet must not contain null");
            if (!worldUuid.equals(chunk.chunk().worldId())) {
                throw new IllegalArgumentException("chunk worldUuid does not match payload worldUuid");
            }
            if (!coordinates.add(new ChunkCoordinate(chunk.chunk()))) {
                throw new IllegalArgumentException("chunkSet contains a duplicate coordinate");
            }
            copy.add(chunk);
        }
        chunkSet = List.copyOf(copy);
    }

    public static OperationPayload claim(
            UUID operationId,
            UUID actorUuid,
            UUID worldUuid,
            LandId targetLandId,
            List<Chunk> chunkSet,
            long priceMinorUnits,
            String economyProviderId,
            Instant createdAt) {
        return claim(operationId, actorUuid, worldUuid, targetLandId, chunkSet,
                priceMinorUnits, economyProviderId, null, createdAt, "Recovered land");
    }

    public static OperationPayload claim(
            UUID operationId,
            UUID actorUuid,
            UUID worldUuid,
            LandId targetLandId,
            List<Chunk> chunkSet,
            long priceMinorUnits,
            String economyProviderId,
            Instant createdAt,
            String landDisplayName) {
        return claim(operationId, actorUuid, worldUuid, targetLandId, chunkSet, priceMinorUnits,
                economyProviderId, null, createdAt, landDisplayName);
    }

    public static OperationPayload claim(
            UUID operationId,
            UUID actorUuid,
            UUID worldUuid,
            LandId targetLandId,
            List<Chunk> chunkSet,
            long priceMinorUnits,
            String economyProviderId,
            String economyTransactionRef,
            Instant createdAt,
            String landDisplayName) {
        return new OperationPayload(operationId, "CLAIM", actorUuid, worldUuid, targetLandId,
                chunkSet, priceMinorUnits, economyProviderId, economyTransactionRef,
                createdAt, createdAt, CURRENT_SCHEMA_VERSION, landDisplayName, null, null);
    }

    public String toJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        field(json, "operationId", operationId.toString());
        field(json, "operationType", operationType);
        field(json, "actorUuid", actorUuid.toString());
        field(json, "worldUuid", worldUuid.toString());
        nullableField(json, "targetLandId", targetLandId == null ? null : targetLandId.value().toString());
        json.append("\"chunkSet\":[");
        for (int i = 0; i < chunkSet.size(); i++) {
            if (i > 0) json.append(',');
            Chunk chunk = chunkSet.get(i);
            json.append('{');
            field(json, "worldUuid", chunk.chunk().worldId().toString());
            numberField(json, "chunkX", chunk.chunk().chunkX());
            numberField(json, "chunkZ", chunk.chunk().chunkZ());
            numberField(json, "storedMinProtectedY", chunk.storedMinProtectedY());
            field(json, "claimLotId", chunk.claimLotId().toString());
            numberField(json, "costBasisMinorUnits", chunk.costBasisMinorUnits());
            trimComma(json);
            json.append('}');
        }
        json.append("],");
        numberField(json, "priceMinorUnits", priceMinorUnits);
        field(json, "economyProviderId", economyProviderId);
        nullableField(json, "economyTransactionRef", economyTransactionRef);
        field(json, "createdAt", createdAt.toString());
        field(json, "updatedAt", updatedAt.toString());
        numberField(json, "schemaVersion", schemaVersion);
        nullableNumberField(json, "refundNumerator", refundNumerator);
        nullableNumberField(json, "refundDenominator", refundDenominator);
        field(json, "landDisplayName", landDisplayName);
        trimComma(json);
        json.append('}');
        return json.toString();
    }

    public static OperationPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("payload JSON must not be blank");
        }
        Object root = new JsonParser(json).parse();
        if (!(root instanceof MapValue object)) {
            throw new IllegalArgumentException("payload JSON root must be an object");
        }
        String operationId = object.requiredString("operationId");
        String operationType = object.requiredString("operationType");
        UUID actor = uuid(object.requiredString("actorUuid"), "actorUuid");
        UUID world = uuid(object.requiredString("worldUuid"), "worldUuid");
        String target = object.optionalString("targetLandId");
        LandId targetLand = target == null ? null : new LandId(uuid(target, "targetLandId"));
        List<Chunk> chunks = new ArrayList<>();
        List<Object> rawChunks = object.requiredArray("chunkSet");
        for (Object rawChunk : rawChunks) {
            if (!(rawChunk instanceof MapValue chunk)) {
                throw new IllegalArgumentException("chunkSet item must be an object");
            }
            UUID chunkWorld = uuid(chunk.requiredString("worldUuid"), "chunkSet.worldUuid");
            int x = exactInt(chunk.requiredNumber("chunkX"), "chunkX");
            int z = exactInt(chunk.requiredNumber("chunkZ"), "chunkZ");
            int minY = exactInt(chunk.requiredNumber("storedMinProtectedY"), "storedMinProtectedY");
            UUID lot = uuid(chunk.requiredString("claimLotId"), "claimLotId");
            long basis = exactLong(chunk.requiredNumber("costBasisMinorUnits"), "costBasisMinorUnits");
            chunks.add(new Chunk(new ChunkKey(chunkWorld, x, z), minY, lot, basis));
        }
        long price = exactLong(object.requiredNumber("priceMinorUnits"), "priceMinorUnits");
        String provider = object.requiredString("economyProviderId");
        String ref = object.optionalString("economyTransactionRef");
        Instant created = instant(object.requiredString("createdAt"), "createdAt");
        Instant updated = instant(object.requiredString("updatedAt"), "updatedAt");
        int version = exactInt(object.requiredNumber("schemaVersion"), "schemaVersion");
        String displayName = object.optionalString("landDisplayName");
        if (displayName == null) displayName = "Recovered land";
        Long refundNumerator = object.optionalLong("refundNumerator");
        Long refundDenominator = object.optionalLong("refundDenominator");
        return new OperationPayload(uuid(operationId, "operationId"), operationType, actor, world,
                targetLand, chunks, price, provider, ref, created, updated, version, displayName,
                refundNumerator, refundDenominator);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(field + " must be a UUID", failure);
        }
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", failure);
        }
    }

    private static int exactInt(Number value, String field) {
        long parsed = exactLong(value, field);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside int range");
        }
        return (int) parsed;
    }

    private static long exactLong(Number value, String field) {
        if (!(value instanceof Long parsed)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return parsed;
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append('"').append(escape(name)).append("\":\"").append(escape(value)).append("\",");
    }

    private static void nullableField(StringBuilder json, String name, String value) {
        if (value == null) {
            json.append('"').append(name).append("\":null,");
        } else {
            field(json, name, value);
        }
    }

    private static void numberField(StringBuilder json, String name, long value) {
        json.append('"').append(name).append("\":").append(value).append(',');
    }

    private static void nullableNumberField(StringBuilder json, String name, Long value) {
        if (value == null) {
            json.append('"').append(name).append("\":null,");
        } else {
            numberField(json, name, value.longValue());
        }
    }

    private static void trimComma(StringBuilder json) {
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public record Chunk(ChunkKey chunk, int storedMinProtectedY, UUID claimLotId, long costBasisMinorUnits) {
        public Chunk {
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(claimLotId, "claimLotId");
            if (costBasisMinorUnits < 0) {
                throw new IllegalArgumentException("costBasisMinorUnits must not be negative");
            }
        }
    }

    private record ChunkCoordinate(UUID world, int x, int z) {
        private ChunkCoordinate(ChunkKey key) {
            this(key.worldId(), key.chunkX(), key.chunkZ());
        }
    }

    private static final class MapValue {
        private final java.util.Map<String, Object> values;

        private MapValue(java.util.Map<String, Object> values) {
            this.values = values;
        }

        private Object required(String key) {
            if (!values.containsKey(key)) throw new IllegalArgumentException("missing payload field: " + key);
            return values.get(key);
        }

        private String requiredString(String key) {
            Object value = required(key);
            if (!(value instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException("payload field must be a non-blank string: " + key);
            }
            return string;
        }

        private String optionalString(String key) {
            if (!values.containsKey(key)) return null;
            Object value = values.get(key);
            if (value == null) return null;
            if (!(value instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException("payload field must be a non-blank string or null: " + key);
            }
            return string;
        }

        private Number requiredNumber(String key) {
            Object value = required(key);
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("payload field must be a number: " + key);
            }
            return number;
        }

        private Long optionalLong(String key) {
            if (!values.containsKey(key)) return null;
            Object value = values.get(key);
            if (value == null) return null;
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("payload field must be a number or null: " + key);
            }
            if (!(number instanceof Long parsed)) {
                throw new IllegalArgumentException("payload field must be an integer: " + key);
            }
            return parsed;
        }

        @SuppressWarnings("unchecked")
        private List<Object> requiredArray(String key) {
            Object value = required(key);
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException("payload field must be an array: " + key);
            }
            return (List<Object>) list;
        }
    }

    private static final class JsonParser {
        private final String input;
        private int offset;

        private JsonParser(String input) {
            this.input = input;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (offset != input.length()) fail("trailing JSON content");
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (offset >= input.length()) fail("unexpected end of JSON");
            return switch (input.charAt(offset)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private MapValue parseObject() {
            offset++;
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return new MapValue(values);
            while (offset < input.length()) {
                skipWhitespace();
                if (offset >= input.length() || input.charAt(offset) != '"') fail("object key must be a string");
                String key = parseString();
                skipWhitespace();
                require(':');
                if (values.containsKey(key)) fail("duplicate JSON field: " + key);
                values.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) return new MapValue(values);
                require(',');
            }
            fail("unterminated object");
            return new MapValue(values);
        }

        private List<Object> parseArray() {
            offset++;
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return values;
            while (offset < input.length()) {
                values.add(parseValue());
                skipWhitespace();
                if (consume(']')) return values;
                require(',');
            }
            fail("unterminated array");
            return values;
        }

        private String parseString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (offset < input.length()) {
                char c = input.charAt(offset++);
                if (c == '"') return value.toString();
                if (c == '\\') {
                    if (offset >= input.length()) fail("unterminated escape");
                    char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(parseUnicode());
                        default -> fail("invalid escape");
                    }
                } else {
                    if (c < 0x20) fail("control character in string");
                    value.append(c);
                }
            }
            fail("unterminated string");
            return "";
        }

        private char parseUnicode() {
            if (offset + 4 > input.length()) fail("short unicode escape");
            String hex = input.substring(offset, offset + 4);
            offset += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException failure) {
                fail("invalid unicode escape");
                return 0;
            }
        }

        private Object parseNull() {
            if (!input.startsWith("null", offset)) fail("invalid null");
            offset += 4;
            return null;
        }

        private Number parseNumber() {
            int start = offset;
            if (consume('-')) { }
            requireDigits();
            if (consume('.')) {
                requireDigits();
                fail("fractional numbers are not supported");
            }
            if (consume('e') || consume('E')) {
                if (consume('+') || consume('-')) { }
                requireDigits();
                fail("exponential numbers are not supported");
            }
            try {
                return Long.valueOf(input.substring(start, offset));
            } catch (NumberFormatException failure) {
                fail("invalid integer");
                return 0L;
            }
        }

        private void requireDigits() {
            int start = offset;
            while (offset < input.length() && Character.isDigit(input.charAt(offset))) offset++;
            if (start == offset) fail("expected digits");
        }

        private void skipWhitespace() {
            while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) offset++;
        }

        private boolean consume(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) fail("expected '" + expected + "'");
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + " at offset " + offset);
        }
    }
}
