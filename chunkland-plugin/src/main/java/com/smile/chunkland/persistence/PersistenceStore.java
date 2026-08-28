package com.smile.chunkland.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Owns the plugin's SQLite connection and the only thread allowed to use it.
 *
 * <p>The package-private {@link #submitAsync(SqlWork)} seam is intentionally
 * the only way repository code can submit a JDBC operation. It submits work to
 * this store's single executor and does not expose the connection to public
 * callers. A callback must not retain the callback connection after returning;
 * the store owns and closes it.
 */
public final class PersistenceStore implements AutoCloseable {

    public static final int INITIAL_SCHEMA_VERSION = 0;

    private static final String PERSISTENCE_THREAD_NAME = "chunkland-persistence";
    private static final String WAL_MODE = "wal";

    private final Path databasePath;
    private final ConnectionOpener connectionOpener;
    private final ExecutorService executor;
    private final Object lifecycleLock = new Object();
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile Lifecycle lifecycle = Lifecycle.OPEN;
    private volatile Thread persistenceThread;
    private volatile Connection connection;
    private volatile boolean jdbcConnectionClosed;

    private PersistenceStore(Path databasePath, ConnectionOpener connectionOpener) {
        this.databasePath = databasePath;
        this.connectionOpener = connectionOpener;
        this.executor = Executors.newSingleThreadExecutor(new PersistenceThreadFactory());
    }

    /** Opens or creates the database and completes the bootstrap on its owner thread. */
    public static PersistenceStore open(Path databasePath) {
        return open(databasePath, PersistenceStore::openConnection);
    }

    static PersistenceStore open(Path databasePath, ConnectionOpener connectionOpener) {
        return open(databasePath, connectionOpener, ignored -> {});
    }

    static PersistenceStore open(
            Path databasePath,
            ConnectionOpener connectionOpener,
            Consumer<PersistenceStore> storeObserver) {
        Objects.requireNonNull(databasePath, "databasePath");
        Objects.requireNonNull(connectionOpener, "connectionOpener");
        Objects.requireNonNull(storeObserver, "storeObserver");
        Path normalizedPath = databasePath.toAbsolutePath().normalize();
        createParentDirectory(normalizedPath);

        PersistenceStore store = new PersistenceStore(normalizedPath, connectionOpener);
        try {
            storeObserver.accept(store);
            store.start();
            return store;
        } catch (RuntimeException | Error failure) {
            try {
                store.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Returns the journal mode asynchronously without waiting for SQL work. */
    public CompletionStage<String> journalModeAsync() {
        return submitAsync(PersistenceStore::readJournalMode);
    }

    /** Returns the schema version asynchronously without waiting for SQL work. */
    public CompletionStage<Integer> schemaVersionAsync() {
        return submitAsync(PersistenceStore::readSchemaVersion);
    }

    /** Blocking bootstrap/test helper; runtime callers must use the async path. */
    String journalMode() {
        return execute(PersistenceStore::readJournalMode);
    }

    /** Blocking bootstrap/test helper; runtime callers must use the async path. */
    int schemaVersion() {
        return execute(PersistenceStore::readSchemaVersion);
    }

    /**
     * Shuts down the connection and persistence executor; accepted work drains
     * before close, new work is rejected once shutdown starts, and repeats are
     * no-ops. This is a lifecycle operation and must not run on a region task.
     */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        Future<?> closeTask;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            if (Thread.currentThread() == persistenceThread) {
                throw new IllegalStateException("close must be called outside the persistence thread");
            }
            if (lifecycle == Lifecycle.CLOSING) {
                awaitTermination();
                return;
            }

            lifecycle = Lifecycle.CLOSING;
            try {
                closeTask = executor.submit(this::closeConnectionOnPersistenceThread);
            } catch (RejectedExecutionException rejected) {
                lifecycle = Lifecycle.CLOSED;
                executor.shutdownNow();
                terminated.countDown();
                throw new PersistenceException("Persistence executor rejected its shutdown task", rejected);
            }
        }

        RuntimeException failure = null;
        try {
            await(closeTask);
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        } finally {
            executor.shutdown();
            awaitExecutorTermination();
            lifecycle = Lifecycle.CLOSED;
            terminated.countDown();
        }

        if (failure != null) {
            throw failure;
        }
    }

    public boolean isClosed() {
        return lifecycle == Lifecycle.CLOSED;
    }

    boolean isClosing() {
        return lifecycle == Lifecycle.CLOSING;
    }

    <T> CompletableFuture<T> submitAsync(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            ensureOpen();
            try {
                executor.execute(
                        () -> {
                            try {
                                result.complete(runSqlWork(work));
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (RejectedExecutionException rejected) {
                throw closedException(rejected);
            }
        }
        return result;
    }

    <T> T execute(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (Thread.currentThread() == persistenceThread) {
            synchronized (lifecycleLock) {
                ensureOpen();
                return runSqlWork(work);
            }
        }
        return await(submitAsync(work));
    }

    <T> T executeDirect(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        assertPersistenceThread();
        return runSqlWork(work);
    }

    Connection connectionForPersistenceThread() {
        if (Thread.currentThread() != persistenceThread) {
            throw new DirectSqlAccessException(
                    "SQL connections may only be used on the persistence thread");
        }
        Connection current = connection;
        if (current == null || jdbcConnectionClosed) {
            throw new PersistenceClosedException("The SQLite connection is closed");
        }
        return current;
    }

    boolean isJdbcConnectionClosed() {
        return jdbcConnectionClosed;
    }

    private void start() {
        Future<?> initialization;
        synchronized (lifecycleLock) {
            initialization = executor.submit(
                    () -> {
                        initializeOnPersistenceThread();
                        return null;
                    });
        }
        await(initialization);
    }

    private void initializeOnPersistenceThread() throws SQLException {
        assertPersistenceThread();
        Connection opened = null;
        try {
            opened = connectionOpener.open(databasePath);
            connection = opened;
            jdbcConnectionClosed = false;
            verifyWal(opened);
            initializeSchemaVersion(opened);
            enableForeignKeys(opened);
            SchemaMigrator.migrate(opened);
        } catch (SQLException | RuntimeException | Error failure) {
            if (opened != null) {
                closeAfterInitializationFailure(opened, failure);
            }
            throw failure;
        }
    }

    private static void enableForeignKeys(Connection opened) throws SQLException {
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static void verifyWal(Connection opened) throws SQLException {
        try (Statement statement = opened.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            if (!resultSet.next()) {
                throw new SQLException("SQLite did not return a journal mode");
            }
            String mode = resultSet.getString(1);
            if (!WAL_MODE.equalsIgnoreCase(mode)) {
                throw new SQLException("SQLite journal_mode is not WAL: " + mode);
            }
        }
    }

    private static void initializeSchemaVersion(Connection opened) throws SQLException {
        boolean committed = false;
        opened.setAutoCommit(false);
        Throwable primary = null;
        try {
            try (Statement statement = opened.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS schema_version ("
                                + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                                + "version INTEGER NOT NULL)");
                statement.executeUpdate(
                        "INSERT OR IGNORE INTO schema_version (id, version) VALUES (1, "
                                + INITIAL_SCHEMA_VERSION
                                + ")");
            }
            SchemaVersion.readCurrent(opened);
            opened.commit();
            committed = true;
        } catch (Throwable failure) {
            primary = failure;
        } finally {
            if (!committed && primary != null) {
                try {
                    opened.rollback();
                } catch (Throwable rollbackFailure) {
                    primary.addSuppressed(rollbackFailure);
                }
            }
            try {
                opened.setAutoCommit(true);
            } catch (Throwable autoCommitFailure) {
                if (primary != null) {
                    primary.addSuppressed(autoCommitFailure);
                } else {
                    primary = autoCommitFailure;
                }
            }
        }
        if (primary != null) {
            if (primary instanceof SQLException sql) {
                throw sql;
            }
            if (primary instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (primary instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unexpected schema initialization failure", primary);
        }
    }

    private static String readJournalMode(Connection opened) throws SQLException {
        try (Statement statement = opened.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode")) {
            if (!resultSet.next()) {
                throw new SQLException("SQLite did not return a journal mode");
            }
            return resultSet.getString(1).toLowerCase(Locale.ROOT);
        }
    }

    private static int readSchemaVersion(Connection opened) throws SQLException {
        return SchemaVersion.readCurrent(opened);
    }

    private void closeConnectionOnPersistenceThread() {
        assertPersistenceThread();
        Connection current = connection;
        if (current == null || jdbcConnectionClosed) {
            jdbcConnectionClosed = true;
            return;
        }
        try {
            current.close();
        } catch (SQLException failure) {
            throw new PersistenceException("Failed to close the SQLite connection", failure);
        } finally {
            jdbcConnectionClosed = true;
        }
    }

    private void closeAfterInitializationFailure(Connection opened, Throwable failure) {
        boolean closed = false;
        try {
            opened.close();
            closed = true;
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        } finally {
            jdbcConnectionClosed = closed;
        }
    }

    private static Connection openConnection(Path databasePath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private <T> T runSqlWork(SqlWork<T> work) {
        try {
            return work.run(connectionForPersistenceThread());
        } catch (SQLException failure) {
            throw new PersistenceException("SQLite operation failed", failure);
        }
    }

    private void assertPersistenceThread() {
        if (Thread.currentThread() != persistenceThread) {
            throw new DirectSqlAccessException(
                    "SQL connections may only be used on the persistence thread");
        }
    }

    private void ensureOpen() {
        if (lifecycle != Lifecycle.OPEN) {
            throw closedException(null);
        }
    }

    private static PersistenceClosedException closedException(Throwable cause) {
        PersistenceClosedException failure =
                new PersistenceClosedException("Persistence is closed or shutting down");
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    private <T> T await(Future<T> task) {
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PersistenceException("Interrupted while waiting for persistence work", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new PersistenceException("Persistence work failed", cause);
        }
    }

    private void awaitTermination() {
        boolean interrupted = false;
        while (true) {
            try {
                if (terminated.await(1, TimeUnit.DAYS)) {
                    break;
                }
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitExecutorTermination() {
        boolean interrupted = false;
        while (true) {
            try {
                if (executor.awaitTermination(1, TimeUnit.DAYS)) {
                    break;
                }
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void createParentDirectory(Path databasePath) {
        Path parent = databasePath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new PersistenceException("Unable to create the SQLite database directory", failure);
        }
    }

    private enum Lifecycle {
        OPEN,
        CLOSING,
        CLOSED
    }

    private final class PersistenceThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, PERSISTENCE_THREAD_NAME);
            thread.setDaemon(false);
            persistenceThread = thread;
            return thread;
        }
    }
}
