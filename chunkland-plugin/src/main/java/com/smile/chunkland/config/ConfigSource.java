package com.smile.chunkland.config;

/**
 * Explicit startup source for the active config snapshot.
 *
 * <p>The source decides the fallback policy, and the policy never gets
 * looser than the operator's own last validated file:
 *
 * <ul>
 *   <li>{@code FILE_VALID} — the file parsed. Use it, refresh the
 *       last-known-good copy.</li>
 *   <li>{@code FIRST_INSTALL} — no file and no trace of previous use. Seed
 *       the shipped defaults to disk exactly once and run them; tell the
 *       operator to edit and restart.</li>
 *   <li>{@code MISSING_AFTER_USE} — no file, but previous use left traces.
 *       Use the last-known-good copy (or conservative defaults); never land
 *       the shipped defaults over an established server.</li>
 *   <li>{@code CORRUPT} — the file exists but does not parse. Use the
 *       last-known-good copy (or conservative defaults); never use the
 *       shipped resource and never modify the operator's file.</li>
 * </ul>
 */
public enum ConfigSource {
    /** The operator's file parsed; refresh the last-known-good copy. */
    FILE_VALID,
    /** No file and no previous state; seed shipped defaults once. */
    FIRST_INSTALL,
    /** No file but previous state exists; fall back without seeding. */
    MISSING_AFTER_USE,
    /** The file exists but is invalid; fall back without touching it. */
    CORRUPT
}
