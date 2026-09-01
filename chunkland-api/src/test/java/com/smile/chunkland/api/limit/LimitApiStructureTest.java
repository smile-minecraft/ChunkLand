package com.smile.chunkland.api.limit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LimitApiStructureTest {

    @Test
    void limitPackageHasNoForbiddenImports() throws IOException {
        var candidates = new ArrayList<Path>();
        String userDir = System.getProperty("user.dir");
        candidates.add(Paths.get(userDir, "src/main/java/com/smile/chunkland/api/limit"));
        candidates.add(Paths.get(userDir, "chunkland-api/src/main/java/com/smile/chunkland/api/limit"));
        try {
            var loc = LimitApiStructureTest.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path moduleDir = Paths.get(loc);
            for (int i = 0; i < 4; i++) moduleDir = moduleDir.getParent();
            candidates.add(moduleDir.resolve("src/main/java/com/smile/chunkland/api/limit"));
        } catch (Exception ignored) {}
        Path base = candidates.stream().filter(Files::exists).findFirst().orElse(null);
        assertTrue(base != null && Files.exists(base), "limit source dir not found among: " + candidates);
        var forbidden = List.of("org.bukkit", "org.spigotmc", "io.papermc", "net.kyori",
                "com.mojang", "java.sql", "javax.sql", "com.smile.acelib");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().startsWith("import ")) {
                        for (String f : forbidden) {
                            if (line.contains(f)) violations.add(file.getFileName() + ":" + (i + 1) + ": " + line.trim());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Forbidden imports found in limit package:\n" + String.join("\n", violations));
    }

    @Test
    void limitResultIsImmutable() {
        LimitResult r = LimitResult.of(5, LimitSource.CONFIG);
        assertTrue(r.limit() == 5);
        assertTrue(r.source() == LimitSource.CONFIG);
    }
}
