# Contributing to ChunkLand

## Before you build

You need **Java 25** and the **AceLib 1.3.0** plugin jar. AceLib is a
compile-only dependency resolved from a local cache directory, so it has to be
there before Gradle can configure the project:

```bash
./scripts/build-acelib.sh
```

That script downloads a fixed AceLib 1.3.0 GitHub Release asset and verifies it
against a pinned SHA-256, the plugin descriptor version, and the presence of
`AceLibVersion.class`. It fails closed: a wrong digest, a wrong version, a
non-JAR response or a missing class all abort with a non-zero exit. The output
lands in `$XDG_CACHE_HOME/chunkland-acelib` (usually
`~/.cache/chunkland-acelib`); set `ACE_OUTPUT_DIR` to change it.

Nothing fetches a floating version. `SNAPSHOT`, `latest` and `mavenLocal()` are
not used for AceLib.

## Build and test

```bash
./gradlew build --no-daemon --console=plain
```

`build` runs `check`, so the test suite runs too. Useful subsets:

```bash
./gradlew :chunkland-api:test --no-daemon --console=plain
./gradlew :chunkland-plugin:test --no-daemon --console=plain
```

The plugin jar is `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar`. It is
self-contained: `chunkland-api`, SQLite and SnakeYAML are embedded, and
compile-only dependencies (paper-api, AceLib, VaultAPI, LuckPerms API) are not.
A separate API jar is produced at
`chunkland-api/build/libs/chunkland-api-0.1.0.jar`.

Archives are built with file timestamps dropped and entry order fixed, so two
clean builds of the same source produce byte-identical jars. If you need that
property, verify with `clean build` twice and compare SHA-256 — it is easy to
break by adding a task that writes non-deterministically.

## Layout

| Module | Responsibility | Rule |
| --- | --- | --- |
| `chunkland-api` | Domain types, `ChunkLandApi`, event bus interface, geometry, pricing and money types | Pure domain. No Bukkit, no SQL, no AceLib on the production classpath |
| `chunkland-plugin` | The server plugin: protection, persistence, commands, GUI and forms, economy, config | The only module that touches Bukkit |

`chunkland-api` has a build guard wired into `check` and `jar` that fails the
build if any external production dependency is declared on it. If you hit that
error, the dependency belongs in `chunkland-plugin`.

## Two invariants worth knowing before you touch code

**Protection is fail-closed.** The registry starts empty and only becomes
readable for occupancy once the startup rebuild has published a complete
snapshot. Until then an empty registry means "unknown", not "wilderness", so
readers deny rather than accepting a claim over durable land they cannot see.
A decision cache hit is scoped to one policy epoch; any epoch move misses.

**Authorisation writes are compared inside the transaction.** A land-default
write validates the authorisation generation it was shown against the current
database value in the same transaction. In-memory authorisation sources —
config defaults, admin bypass, the server-land steward — do not participate in
that comparison; only the gate before submission sees their revocation.

## Writing code

- Java 25, four-space indent, UTF-8.
- Public types and methods carry JavaDoc with `@param`, `@return` and
  `@throws` filled in.
- If Paper and Folia behave differently, the JavaDoc says which thread or
  region operation the caller must be on.
- Comments explain why a constraint exists — the invariant, the failure mode the
  code is avoiding. Do not narrate what the next line does, and do not put
  tracking numbers, task identifiers or plan references in comments.

Generate JavaDoc with doclint enabled:

```bash
./gradlew javadoc --no-daemon --console=plain
```

## Working on Folia

Dispatch player, entity, block and inventory work through AceLib's safe
scheduler, and validate the thread context. Region threading is not something a
test can prove for you; the real server is the check that counts.

The shared Folia test server lives outside this repository. Its data directory is
locked by the server process, so stop the server before reading the SQLite
database, the LuckPerms store or the CoreProtect database directly, or use RCON
through the plugin's own commands.

## Documentation

Every user-facing document exists in three languages under `docs/en`,
`docs/zh-TW` and `docs/zh-CN`, with the same file names. When you change one,
change all three in the same change. `LIMITATIONS.md` at the repository root is
the Traditional Chinese delivery copy; `docs/en/limitations.md` and
`docs/zh-CN/limitations.md` are its translations.

`docs/documentation-style.md` says where content belongs and how pages are
shaped. Read it before adding a page.

House rules for anything published here:

- Normative words — must, must not, default — have to be traceable to code,
  configuration, a test, or official documentation.
- Version numbers, defaults, ranges and file paths are copied from the source,
  not from memory.
- No internal tracking identifiers, milestone numbers, task identifiers, agent
  process, test counts, or absolute local paths in published files.
- Do not document a release or coordinate that does not exist. The obtain-ChunkLand
  path currently is the `0.1.0` GitHub Release
  (<https://github.com/smile-minecraft/ChunkLand/releases/tag/v0.1.0>) for the plugin
  jar and the JitPack (`https://jitpack.io`) root coordinate
  `com.github.smile-minecraft:ChunkLand:v0.1.0` for the API; building from a
  checkout stays a documented alternative, not the only way in.
- An integration sample has to compile with the dependencies it tells the reader
  to add. The JitPack coordinate carries the API module only, so a sample that
  names `com.smile.chunkland.ChunkLandPlugin` — the class declaring `getReadApi()`
  and `publicEventBus()` — also needs the plugin jar as a second `compileOnly`
  entry. Verify by compiling the sample, not by reading the sample.

## Reporting a problem

Include the Folia build, the Java version, the AceLib version, what you expected
and what happened instead. If protection refused an action you believe was
allowed, `/land explain <action>` from inside the land is the single most useful
thing you can attach — it reports the outcome, the deciding layer, and whether
ownership, admin bypass or the steward role applied.

## License

Contributions are accepted under the [MIT License](LICENSE).