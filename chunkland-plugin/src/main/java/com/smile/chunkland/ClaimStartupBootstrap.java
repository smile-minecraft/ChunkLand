package com.smile.chunkland;

import com.smile.chunkland.api.money.Currency;
import com.smile.chunkland.claim.ClaimEconomy;
import com.smile.chunkland.claim.ClaimRecoveryHandlers;
import com.smile.chunkland.claim.RuntimeRegistryRebuilder;
import com.smile.chunkland.claim.VaultClaimEconomy;
import com.smile.chunkland.economy.UnavailableVaultBridge;
import com.smile.chunkland.economy.VaultBridge;
import com.smile.chunkland.persistence.OperationLedger;
import com.smile.chunkland.persistence.PersistenceStore;
import com.smile.chunkland.persistence.RecoveryResult;
import com.smile.chunkland.persistence.SqliteChunkRepository;
import com.smile.chunkland.persistence.SqliteLandRepository;
import com.smile.chunkland.runtime.index.LandRegistryStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Production startup caller for claim crash recovery.
 *
 * <p>{@link ChunkLandPlugin} owns exactly one instance: {@code onEnable}
 * starts it with the shared protection store, {@code onDisable} closes it.
 * Startup opens the SQLite store, builds the ledger, the authoritative land
 * repository, a fail-closed Economy, and a rebuilder that publishes into the
 * same {@link LandRegistryStore} the protection engine reads, then runs
 * {@link ClaimRecoveryHandlers#scanAtStartup} without blocking the caller.
 * The store stays empty (wilderness) until the scan publishes; a failed
 * rebuild never marks rows active and never refunds.
 *
 * <p>Economy note: the bridge is injected by the lifecycle owner so recovery
 * can refund through a real provider when one is available. A null or
 * unavailable bridge resolves to the explicitly unavailable bridge: charges
 * never happen during recovery and refunds through it fail closed instead of
 * moving money, so unrefunded rows stay retryable instead of misreporting
 * success.
 *
 * <p>Threading: {@code start} performs the synchronous SQLite bootstrap the
 * store contract requires, then returns immediately; the scan itself runs on
 * the persistence executor. This class never blocks on the scan future and
 * never runs SQL on a region thread. Closing is idempotent, and a scan that
 * finishes after close only logs: it never writes back into cleared plugin
 * state.
 */
public final class ClaimStartupBootstrap implements AutoCloseable {

    /** SQLite file name inside the plugin data folder. */
    public static final String DATABASE_FILE_NAME = "chunkland.db";

    /**
     * Claim currency until the production config gains a currency source.
     * Matches the currency the claim tests already standardise on; a real
     * provider must replace this constant before Vault is connected.
     */
    static final Currency CLAIM_CURRENCY = Currency.of("EMC", 2);

    private final PersistenceStore store;
    private final OperationLedger ledger;
    private final ClaimEconomy economy;
    private final RuntimeRegistryRebuilder rebuilder;
    private final CompletionStage<List<RecoveryResult>> scan;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ClaimStartupBootstrap(PersistenceStore store, OperationLedger ledger,
            ClaimEconomy economy, RuntimeRegistryRebuilder rebuilder,
            CompletionStage<List<RecoveryResult>> scan, Logger logger) {
        this.store = store;
        this.ledger = ledger;
        this.economy = economy;
        this.rebuilder = rebuilder;
        this.scan = scan;
        this.logger = logger;
    }

    /**
     * Open persistence and trigger the startup recovery scan with the default
     * fail-closed Economy: no Vault provider is resolved yet, so recovery runs
     * on an explicitly unavailable bridge and unrefunded rows stay retryable.
     *
     * @param databasePath the SQLite file to open (parent directories are created)
     * @param sharedStore the registry store shared with the protection engine
     * @param logger log sink for scan completion; never null in production
     * @return the running bootstrap, owned by the caller
     */
    public static ClaimStartupBootstrap start(Path databasePath, LandRegistryStore sharedStore, Logger logger) {
        return start(databasePath, sharedStore, logger, new UnavailableVaultBridge());
    }

    /**
     * Open persistence and trigger the startup recovery scan with an explicit
     * Economy bridge.
     *
     * <p>Lifecycle: an available bridge lets {@code CHARGED} and
     * {@code COMPENSATION_PENDING} rows refund for real; an unavailable bridge
     * keeps recovery fail-safe — refunds fail closed and rows stay on a
     * retryable state for a later restart with a live provider instead of
     * advancing. A null bridge resolves to unavailable.
     *
     * @param databasePath the SQLite file to open (parent directories are created)
     * @param sharedStore the registry store shared with the protection engine
     * @param logger log sink for scan completion; never null in production
     * @param bridge Economy provider bridge; null resolves to unavailable
     * @return the running bootstrap, owned by the caller
     */
    public static ClaimStartupBootstrap start(Path databasePath, LandRegistryStore sharedStore, Logger logger,
            VaultBridge bridge) {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(sharedStore, "sharedStore");
        VaultBridge active = bridge == null ? new UnavailableVaultBridge() : bridge;
        PersistenceStore store = PersistenceStore.open(databasePath);
        boolean started = false;
        try {
            OperationLedger ledger = new OperationLedger(store);
            ClaimEconomy economy = new VaultClaimEconomy(active, CLAIM_CURRENCY);
            RuntimeRegistryRebuilder rebuilder =
                    new RuntimeRegistryRebuilder(new SqliteLandRepository(store), sharedStore,
                            new SqliteChunkRepository(store));
            CompletionStage<List<RecoveryResult>> scan =
                    ClaimRecoveryHandlers.scanAtStartup(ledger, economy, rebuilder);
            ClaimStartupBootstrap bootstrap =
                    new ClaimStartupBootstrap(store, ledger, economy, rebuilder, scan, logger);
            bootstrap.observe(scan);
            started = true;
            return bootstrap;
        } finally {
            if (!started) {
                try {
                    store.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (logger != null) {
                        logger.warning("ChunkLand claim recovery bootstrap cleanup failed: " + closeFailure);
                    }
                }
            }
        }
    }

    OperationLedger ledger() {
        return ledger;
    }

    PersistenceStore store() {
        return store;
    }

    ClaimEconomy economy() {
        return economy;
    }

    RuntimeRegistryRebuilder rebuilder() {
        return rebuilder;
    }

    CompletionStage<List<RecoveryResult>> scanFuture() {
        return scan;
    }

    boolean isClosed() {
        return closed.get();
    }

    /**
     * Close the owned persistence store. Idempotent: repeats are no-ops and a
     * scan that completes afterwards only logs instead of touching cleared
     * state.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            store.close();
        } catch (RuntimeException failure) {
            if (logger != null) {
                logger.warning("ChunkLand claim recovery store close failed: " + failure.getMessage());
            }
        }
    }

    private void observe(CompletionStage<List<RecoveryResult>> scan) {
        try {
            scan.whenComplete((results, failure) -> {
                try {
                    if (closed.get()) {
                        return;
                    }
                    if (logger == null) {
                        return;
                    }
                    if (failure != null) {
                        logger.warning("ChunkLand startup recovery scan failed; "
                                + "runtime stays empty until the next restart: " + failure);
                    } else {
                        logger.info("ChunkLand startup recovery scan finished: "
                                + (results == null ? 0 : results.size()) + " rows");
                    }
                } catch (RuntimeException | Error ignored) {
                    // Observer only; recovery results are durable in the ledger.
                }
            });
        } catch (RuntimeException | Error registrationFailure) {
            if (logger != null) {
                logger.warning("ChunkLand startup recovery observer was rejected: " + registrationFailure);
            }
        }
    }
}
