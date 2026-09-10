package com.smile.chunkland.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.chunkland.api.permission.PermissionState;
import com.smile.chunkland.api.permission.ProtectionActionType;
import com.smile.chunkland.persistence.PermissionProfileRepository;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.ProfileRejectedException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Command-facing entry for player-owned Permission Profiles: owner-scoped
 * resolution plus fail-closed text parsing for permission and state names.
 */
class PermissionProfileServiceTest {

    @TempDir Path tmp;

    private static String reasonOf(CompletionException failure) {
        Throwable cause = failure.getCause();
        while ((cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof ProfileRejectedException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("expected ProfileRejectedException, got " + cause, failure);
    }

    private PermissionProfileService service(PersistenceStore store) {
        return new PermissionProfileService(new PermissionProfileRepository(store),
                Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createListResolveRoundTripInOwnNamespace() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve("svc.db"))) {
            PermissionProfileService profiles = service(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView created =
                    profiles.create(owner, "Builders").toCompletableFuture().join();
            assertEquals(owner, created.owner());
            assertEquals("builders", created.nameKey());
            assertEquals(1, profiles.list(owner).toCompletableFuture().join().size());

            assertEquals(created.id(), profiles.resolve(owner, created.id().toString())
                    .toCompletableFuture().join().id());
            assertEquals(created.id(), profiles.resolve(owner, "  BUILDERS  ")
                    .toCompletableFuture().join().id());
        }
    }

    @Test
    void resolveUnknownOrForeignRefFailsClosed() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve("unknown.db"))) {
            PermissionProfileService profiles = service(store);
            UUID owner = UUID.randomUUID();
            UUID foreign = UUID.randomUUID();
            PermissionProfileRepository.ProfileView mine =
                    profiles.create(owner, "Mine").toCompletableFuture().join();
            profiles.create(foreign, "Mine").toCompletableFuture().join();

            CompletionException missing = assertThrows(CompletionException.class, () ->
                    profiles.resolve(owner, UUID.randomUUID().toString()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(missing));
            CompletionException badName = assertThrows(CompletionException.class, () ->
                    profiles.resolve(owner, "Nobody").toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(badName));
            // A foreign owner's profile id never resolves here.
            PermissionProfileRepository.ProfileView theirs = profiles.list(foreign)
                    .toCompletableFuture().join().get(0);
            CompletionException foreignRef = assertThrows(CompletionException.class, () ->
                    profiles.resolve(owner, theirs.id().toString()).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(foreignRef));
            assertEquals(mine.id(), profiles.resolve(owner, mine.id().toString())
                    .toCompletableFuture().join().id());
        }
    }

    @Test
    void setEntryParsesTextFailClosed() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve("parse.db"))) {
            PermissionProfileService profiles = service(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView created =
                    profiles.create(owner, "Crew").toCompletableFuture().join();

            PermissionProfileRepository.EntryOutcome outcome = profiles
                    .setEntry(owner, created.id().toString(), "entry", "allow")
                    .toCompletableFuture().join();
            assertEquals(ProtectionActionType.ENTRY, outcome.action());
            assertEquals(PermissionState.ALLOW, outcome.after());

            for (String badPermission : new String[] {"", "   ", "EVERYONE", "*", "NOPE",
                    "PISTON_MOVE", "piston_move", "FLUID_FLOW", "MANAGE_X"}) {
                CompletionException rejected = assertThrows(CompletionException.class, () ->
                        profiles.setEntry(owner, created.id().toString(), badPermission, "ALLOW")
                                .toCompletableFuture().join());
                assertEquals("profile.invalid_permission", reasonOf(rejected),
                        "permission '" + badPermission + "' must fail closed");
            }
            for (String badState : new String[] {"", "   ", "YES", "TRUE", "INHERITX"}) {
                CompletionException rejected = assertThrows(CompletionException.class, () ->
                        profiles.setEntry(owner, created.id().toString(), "ENTRY", badState)
                                .toCompletableFuture().join());
                assertEquals("profile.invalid_state", reasonOf(rejected),
                        "state '" + badState + "' must fail closed");
            }
            // INHERIT (any case) deletes the row instead of storing a value.
            PermissionProfileRepository.EntryOutcome cleared = profiles
                    .setEntry(owner, created.id().toString(), "ENTRY", "inherit")
                    .toCompletableFuture().join();
            assertEquals(PermissionState.INHERIT, cleared.after());
            assertTrue(profiles.resolve(owner, created.id().toString())
                    .toCompletableFuture().join().entries().isEmpty());
        }
    }

    @Test
    void managementSubjectPermissionsAreAcceptedButLandRulesAreNot() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve("scope.db"))) {
            PermissionProfileService profiles = service(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView created =
                    profiles.create(owner, "Crew").toCompletableFuture().join();
            // SUBJECT_PERMISSION management actions stay settable at profile level.
            profiles.setEntry(owner, created.id().toString(), "MANAGE_MEMBER", "DENY")
                    .toCompletableFuture().join();
            assertEquals(PermissionState.DENY, profiles
                    .resolve(owner, created.id().toString()).toCompletableFuture().join()
                    .entries().get(ProtectionActionType.MANAGE_MEMBER));
        }
    }

    private static String shortenUuid(String canonical) {
        String[] groups = canonical.split("-");
        StringBuilder out = new StringBuilder();
        for (int g = 0; g < groups.length; g++) {
            if (g > 0) {
                out.append('-');
            }
            String group = groups[g];
            int i = 0;
            while (i + 1 < group.length() && group.charAt(i) == '0') {
                i++;
            }
            out.append(group.substring(i));
        }
        return out.toString();
    }

    @Test
    void resolveRejectsNonCanonicalUuidText() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve("canonical.db"))) {
            PermissionProfileService profiles = service(store);
            UUID owner = UUID.randomUUID();
            // Find a profile whose id has a strippable leading zero, so the
            // shortened text parses to the same UUID but is non-canonical.
            PermissionProfileRepository.ProfileView created = null;
            String canonical = null;
            String alias = null;
            for (int attempt = 0; attempt < 100 && alias == null; attempt++) {
                PermissionProfileRepository.ProfileView candidate =
                        profiles.create(owner, "Crew" + attempt).toCompletableFuture().join();
                String text = candidate.id().toString();
                String shortForm = shortenUuid(text);
                if (!shortForm.equals(text)) {
                    created = candidate;
                    canonical = text;
                    alias = shortForm;
                }
            }
            assertNotNull(alias,
                    "expected a profile id with a strippable leading zero within 100 attempts");
            assertEquals(created.id(), UUID.fromString(alias),
                    "test guard: shortened alias must parse to the same UUID");
            int profileCount = profiles.list(owner).toCompletableFuture().join().size();
            // A non-canonical alias of a real profile id must not resolve to it.
            final String nonCanonical = alias;
            CompletionException aliased = assertThrows(CompletionException.class, () ->
                    profiles.resolve(owner, nonCanonical).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(aliased));
            // The reported short form and other malformed refs fail closed too.
            for (String bad : new String[] {"1-1-1-1-1", "0-0-0-0-0", "", "   ",
                    "not-a-uuid", canonical.substring(0, canonical.length() - 1)}) {
                final String ref = bad;
                CompletionException rejected = assertThrows(CompletionException.class, () ->
                        profiles.resolve(owner, ref).toCompletableFuture().join());
                assertEquals("profile.unknown", reasonOf(rejected),
                        "ref '" + bad + "' must fail closed");
            }
            // Case-insensitive canonical text keeps working, matching the
            // durable owner-key UUID contract.
            assertEquals(created.id(), profiles.resolve(owner, canonical.toUpperCase())
                    .toCompletableFuture().join().id());
            assertEquals(created.id(), profiles.resolve(owner, canonical)
                    .toCompletableFuture().join().id());
            // Rejected refs never mutate: set/delete stay fail-closed and the
            // profile is untouched.
            CompletionException setRejected = assertThrows(CompletionException.class, () ->
                    profiles.setEntry(owner, nonCanonical, "ENTRY", "ALLOW").toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(setRejected));
            CompletionException deleteRejected = assertThrows(CompletionException.class, () ->
                    profiles.delete(owner, nonCanonical).toCompletableFuture().join());
            assertEquals("profile.unknown", reasonOf(deleteRejected));
            assertEquals(profileCount, profiles.list(owner).toCompletableFuture().join().size());
            assertTrue(profiles.resolve(owner, canonical)
                    .toCompletableFuture().join().entries().isEmpty());
        }
    }

    @Test
    void deleteAndForceDeleteDelegateWithOwnerAsActor() {
        try (PersistenceStore store = PersistenceStore.open(tmp.resolve("delete.db"))) {
            PermissionProfileService profiles = service(store);
            UUID owner = UUID.randomUUID();
            PermissionProfileRepository.ProfileView first =
                    profiles.create(owner, "First").toCompletableFuture().join();
            profiles.delete(owner, first.id().toString()).toCompletableFuture().join();
            assertTrue(profiles.list(owner).toCompletableFuture().join().isEmpty());

            PermissionProfileRepository.ProfileView second =
                    profiles.create(owner, "Second").toCompletableFuture().join();
            PermissionProfileRepository.ForceDeleteOutcome outcome = profiles
                    .forceDelete(owner, second.id().toString()).toCompletableFuture().join();
            assertEquals(second.id(), outcome.profileId());
            assertTrue(outcome.affected().isEmpty());
        }
    }
}
