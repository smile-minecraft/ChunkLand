package com.smile.chunkland.api.money;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Structural guard: the {@code money} package must never perform floating-point currency
 * arithmetic. This complements the behavioral precision tests in {@link MoneyTest} and
 * {@link PricingTableTest} — it is not a substitute for them.
 *
 * <p>It scans the production source of this package for floating-point primitive types
 * ({@code double}/{@code float}) and floating-point {@code Math.*} conversions, ignoring
 * comments. Integer-only {@code Math} helpers ({@code addExact}, {@code multiplyExact},
 * {@code subtractExact}, {@code negateExact}, {@code abs}, {@code max}, {@code min}) are
 * permitted and intentionally not matched.
 */
class MoneyPackageNoFloatingPointTest {

    private static final Pattern FLOATING_TYPE = Pattern.compile("\\b(double|float)\\b");
    private static final Pattern FLOATING_MATH = Pattern.compile(
            "Math\\.(sin|cos|tan|asin|acos|atan|toRadians|toDegrees|exp|log|log10|sqrt|cbrt|"
                    + "IEEEremainder|ceil|floor|rint|atan2|pow|round|scalb|copySign|nextAfter|"
                    + "nextUp|nextDown|hypot|expm1|log1p)\\b");
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");

    @Test
    void moneyPackageHasNoFloatingPointArithmetic() {
        Path srcRoot = findMoneySourceRoot();
        List<String> violations = new ArrayList<>();
        try (var walk = Files.walk(srcRoot)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .toList();
            for (Path f : files) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String raw = lines.get(i);
                    String stripped = BLOCK_COMMENT.matcher(raw).replaceAll("");
                    stripped = LINE_COMMENT.matcher(stripped).replaceAll("");
                    int lineNo = i + 1;
                    if (FLOATING_TYPE.matcher(stripped).find()) {
                        violations.add(f.getFileName() + ":" + lineNo + " floating type -> " + raw.trim());
                    }
                    if (FLOATING_MATH.matcher(stripped).find()) {
                        violations.add(f.getFileName() + ":" + lineNo + " floating Math.* -> " + raw.trim());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!violations.isEmpty()) {
            fail("money package must not use floating-point arithmetic:\n" + String.join("\n", violations));
        }
    }

    private Path findMoneySourceRoot() {
        try {
            URI uri = Money.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path mainClasses = Path.of(uri);
            Path pkg = Path.of("com/smile/chunkland/api/money");
            Path dir = mainClasses;
            while (dir != null) {
                Path candidate = dir.resolve("src/main/java").resolve(pkg);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                candidate = dir.resolve(pkg);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                dir = dir.getParent();
            }
            throw new IllegalStateException("cannot locate money source root from " + mainClasses);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
