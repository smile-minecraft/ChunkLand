#!/usr/bin/env bash
# Reproducible acceptance test for the chunkland-api publication contract.
#
# What this proves, and what it deliberately does not:
#   1. chunkland-api builds and publishes on its own with an EMPTY AceLib cache, so a
#      JitPack container (which never has AceLib) can build it.
#   2. The published artifact set is complete: binary + sources + javadoc + POM.
#   3. The published POM declares zero dependencies.
#   4. The published jar contains no plugin, Bukkit/Paper, AceLib or SQLite classes.
#   5. An INDEPENDENT consumer project — its own Gradle build, its own settings file,
#      resolved only from the isolated repository — compiles against the public API
#      using the same coordinates a JitPack consumer would use. This is the check that
#      actually proves the coordinates resolve, rather than merely that Gradle wrote
#      some files.
#
# The publication goes to a throwaway repository under a temp dir (via
# -Dmaven.repo.local), so the developer's real ~/.m2/repository is never touched.
#
# What this cannot prove: whether JitPack's server-side coordinate remapping and
# remote artifact serving behave as expected. That requires a pushed tag and a real
# JitPack build; see the acceptance notes in the task report.
#
# Usage: scripts/verify-api-publication.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

REPO="$WORK/repo"
CONSUMER="$WORK/consumer"
COORD_GROUP="com.github.smile-minecraft.ChunkLand"
COORD_ARTIFACT="chunkland-api"
# Version comes from the single source of truth, so this script cannot drift from
# the published version.
VERSION="$(sed -n 's/^project = "\(.*\)"$/\1/p' "$ROOT/gradle/libs.versions.toml")"
if [ -z "$VERSION" ]; then
  echo "FAIL: could not read the project version from gradle/libs.versions.toml" >&2
  exit 1
fi
API_DIR="$REPO/$(echo "$COORD_GROUP" | tr '.' '/')/$COORD_ARTIFACT/$VERSION"

# Empty AceLib cache on purpose: a JitPack container has no AceLib, so the api module
# must be buildable and publishable without it. Pointing ACE_OUTPUT_DIR at a path that
# does not exist also proves nothing silently falls back to a machine-local cache.
EMPTY_ACE="$WORK/no-acelib-here"
mkdir -p "$EMPTY_ACE"

echo "== Publishing chunkland-api to an isolated repository (version $VERSION) =="
( cd "$ROOT" && ACE_OUTPUT_DIR="$EMPTY_ACE" ./gradlew \
    :chunkland-api:apiPublicationCheck \
    :chunkland-api:publishToMavenLocal \
    -x test \
    -Dmaven.repo.local="$REPO" \
    --console=plain )

echo "== Verifying the published artifact set =="
for suffix in "" "-sources" "-javadoc"; do
  file="$API_DIR/$COORD_ARTIFACT-$VERSION$suffix.jar"
  if [ ! -f "$file" ]; then
    echo "FAIL: expected published artifact is missing: $file" >&2
    exit 1
  fi
  echo "  present: $COORD_ARTIFACT-$VERSION$suffix.jar"
done
POM="$API_DIR/$COORD_ARTIFACT-$VERSION.pom"
if [ ! -f "$POM" ]; then
  echo "FAIL: expected published POM is missing: $POM" >&2
  exit 1
fi

echo "== Verifying the published POM declares no dependencies =="
if grep -q "<dependency>" "$POM"; then
  echo "FAIL: published POM declares dependencies; chunkland-api must be self-contained" >&2
  grep -A4 "<dependencies>" "$POM" >&2
  exit 1
fi
for expected in "<groupId>$COORD_GROUP</groupId>" \
                "<artifactId>$COORD_ARTIFACT</artifactId>" \
                "<version>$VERSION</version>"; do
  if ! grep -qF "$expected" "$POM"; then
    echo "FAIL: published POM is missing $expected" >&2
    exit 1
  fi
done
echo "  no <dependency> element; coordinates match $COORD_GROUP:$COORD_ARTIFACT:$VERSION"

echo "== Verifying the published jar carries no plugin / server / SQLite classes =="
# unzip -Z1 lists entries; grepping the listing (not the bytes) avoids false hits from
# compressed content and keeps the check independent of any jar tool on the classpath.
ENTRIES="$(unzip -Z1 "$API_DIR/$COORD_ARTIFACT-$VERSION.jar")"
for prefix in "com/smile/chunkland/gui/" "com/smile/chunkland/land/" \
              "com/smile/chunkland/limit/" "com/smile/chunkland/event/" \
              "com/smile/chunkland/config/" "com/smile/chunkland/permission/" \
              "com/smile/chunkland/selection/" "com/smile/chunkland/capability/" \
              "com/smile/acelib/" "org/bukkit/" "io/papermc/" "net/milkbowl/" \
              "net/luckperms/" "org/sqlite/"; do
  hits="$(printf '%s\n' "$ENTRIES" | grep -F "$prefix" || true)"
  if [ -n "$hits" ]; then
    echo "FAIL: published jar contains entries under '$prefix':" >&2
    printf '%s\n' "$hits" >&2
    exit 1
  fi
done
# The api's own package must still be present: a jar that is trivially empty would
# pass the checks above while publishing nothing usable.
if ! printf '%s\n' "$ENTRIES" | grep -q "^com/smile/chunkland/api/"; then
  echo "FAIL: published jar has no com/smile/chunkland/api classes; nothing was packaged" >&2
  exit 1
fi
echo "  no foreign entries; com/smile/chunkland/api classes present"

echo "== Compiling an independent consumer against the published API =="
# The consumer is a separate Gradle build with its own settings file and its own
# repository list. It resolves the artifact only from the isolated repository, so a
# coordinate typo or a POM that pulls transitive dependencies fails here.
mkdir -p "$CONSUMER/src/main/java/example"
cat > "$CONSUMER/settings.gradle.kts" <<SETTINGS
rootProject.name = "chunkland-api-consumer-check"
SETTINGS
cat > "$CONSUMER/build.gradle.kts" <<BUILD
plugins {
    java
}

repositories {
    maven { url = uri("$REPO") }
}

dependencies {
    implementation("$COORD_GROUP:$COORD_ARTIFACT:$VERSION")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Compile-only: the consumer's own code is checked, not its tests.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
BUILD
# Exercises a representative slice of the public API: land identity, money,
# permission, mutation and the event bus. Each call is a real public signature, so a
# signature change or a missing artifact fails the compile.
cat > "$CONSUMER/src/main/java/example/ApiConsumerCheck.java" <<'JAVA'
package example;

import com.smile.chunkland.api.event.ChunkLandEventBus;
import com.smile.chunkland.api.event.NoopChunkLandEventBus;
import com.smile.chunkland.api.land.ChunkKey;
import com.smile.chunkland.api.land.LandName;
import com.smile.chunkland.api.money.ChunkCoordinate;
import com.smile.chunkland.api.money.CostBasisAllocation;
import com.smile.chunkland.api.money.CostBasisCalculator;
import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.api.money.Money;
import com.smile.chunkland.api.permission.PermissionDecision;
import com.smile.chunkland.api.permission.PermissionResolver;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ApiConsumerCheck {
    private ApiConsumerCheck() {}

    public static String probe() {
        // Land identity: pack/unpack round-trips through the published record.
        UUID world = UUID.randomUUID();
        ChunkKey key = new ChunkKey(world, 1, 2);
        long packed = key.pack();
        ChunkKey restored = ChunkKey.unpack(world, packed);

        // Money and the allocation/refund math.
        Currency currency = Currency.of("econ", 2);
        Money total = new Money(100L, currency);
        Set<ChunkCoordinate> chunks = Set.of(
                new ChunkCoordinate(0, 0),
                new ChunkCoordinate(0, 1));
        CostBasisAllocation allocation = CostBasisCalculator.allocate(total, chunks);
        Money refund = CostBasisCalculator.refund(total, 1L, 2L);

        // Event bus contract reachable without any implementation dependency.
        ChunkLandEventBus bus = NoopChunkLandEventBus.instance();

        // Permission contract referenced by type, so the compile depends on those
        // interfaces staying in the published artifact.
        Class<PermissionResolver> resolverType = PermissionResolver.class;
        Class<PermissionDecision> decisionType = PermissionDecision.class;

        return restored + "/" + LandName.of("Demo").displayName() + "/"
                + allocation.allocations() + "/" + refund + "/" + bus + "/"
                + resolverType + "/" + decisionType + "/" + List.of(packed);
    }
}
JAVA
( cd "$CONSUMER" && "$ROOT/gradlew" compileJava --console=plain --no-daemon )

echo
echo "PASS: chunkland-api publishes and is consumable at $COORD_GROUP:$COORD_ARTIFACT:$VERSION"
echo "  - published binary/sources/javadoc jars + POM, no POM dependencies"
echo "  - jar free of plugin, Bukkit/Paper, AceLib and SQLite entries"
echo "  - independent consumer build compiles against the published coordinates"
echo "  - build succeeded with an empty AceLib cache (JitPack container parity)"