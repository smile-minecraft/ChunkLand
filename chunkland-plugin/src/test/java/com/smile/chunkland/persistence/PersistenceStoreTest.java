package com.smile.chunkland.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class PersistenceStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesWalAndSchemaVersion() {
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            assertEquals("wal", store.journalMode());
            assertEquals("wal", store.journalModeAsync().toCompletableFuture().join());
            assertEquals(SchemaMigrator.LATEST_VERSION, store.schemaVersion());
            assertEquals(
                    1,
                    store.execute(this::schemaVersionTableCount),
                    "schema_version table must be created during bootstrap");
        }
    }

    @Test
    void serializesSqlOnOneDedicatedPersistenceThread() {
        String callerThreadName = Thread.currentThread().getName();
        Set<Long> threadIds = new HashSet<>();
        Set<String> threadNames = new HashSet<>();

        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            for (int attempt = 0; attempt < 8; attempt++) {
                PersistenceThread thread = store.execute(connection -> persistenceThread());
                threadIds.add(thread.id());
                threadNames.add(thread.name());
                assertFalse(thread.daemon());
            }
        }

        assertEquals(1, threadIds.size());
        assertEquals(1, threadNames.size());
        String persistenceThreadName = threadNames.iterator().next();
        assertTrue(persistenceThreadName.contains("persistence"));
        assertNotEquals(callerThreadName, persistenceThreadName);
    }

    @Test
    void rejectsDirectSqlAccessFromCallerThread() {
        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            DirectSqlAccessException exception = assertThrows(
                    DirectSqlAccessException.class,
                    () -> store.executeDirect(PersistenceStoreTest::selectOne));

            assertTrue(exception.getMessage().contains("persistence thread"));
        }
    }

    @Test
    @Timeout(5)
    void shutdownIsIdempotentClosesResourcesAndStopsPersistenceThread() throws SQLException {
        PersistenceStore store = PersistenceStore.open(databasePath());
        AtomicReference<Connection> connectionReference = new AtomicReference<>();
        String persistenceThreadName = store.execute(
                connection -> {
                    connectionReference.set(connection);
                    return Thread.currentThread().getName();
                });
        AtomicBoolean secondCloseCompleted = new AtomicBoolean();

        store.shutdown();
        store.close();
        secondCloseCompleted.set(true);

        assertTrue(secondCloseCompleted.get());
        assertTrue(store.isClosed());
        assertTrue(store.isJdbcConnectionClosed());
        assertNotNull(connectionReference.get());
        assertTrue(connectionReference.get().isClosed());
        assertThrows(PersistenceClosedException.class, store::journalMode);
        assertThrows(
                PersistenceClosedException.class,
                () -> store.execute(PersistenceStoreTest::selectOne));
        assertFalse(hasLiveThread(persistenceThreadName));
    }

    @Test
    @Timeout(5)
    void asyncSubmissionReturnsWhilePersistenceWorkIsBlocked() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (PersistenceStore store = PersistenceStore.open(databasePath())) {
            CompletionStage<PersistenceThread> blocked = store.submitAsync(
                    connection -> {
                        started.countDown();
                        awaitRelease(release);
                        return selectThreadAfterQuery(connection);
                    });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(blocked.toCompletableFuture().isDone());
            CompletionStage<String> mode = store.journalModeAsync();
            assertFalse(mode.toCompletableFuture().isDone());

            release.countDown();
            PersistenceThread thread = blocked.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(thread.name().contains("persistence"));
            assertEquals("wal", mode.toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(5)
    void closeDrainsAcceptedWorkAndRejectsWorkSubmittedAfterClosing() throws Exception {
        PersistenceStore store = PersistenceStore.open(databasePath());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedWorkRan = new AtomicBoolean();
        ExecutorService closerExecutor = Executors.newSingleThreadExecutor();

        try {
            CompletionStage<Void> inFlight = store.submitAsync(
                    connection -> {
                        started.countDown();
                        awaitRelease(release);
                        return null;
                    });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            CompletionStage<Void> queued = store.submitAsync(
                    connection -> {
                        queuedWorkRan.set(true);
                        return null;
                    });
            Future<?> closeFuture = closerExecutor.submit(store::close);

            assertTimeout(Duration.ofSeconds(2), () -> {
                while (!store.isClosing()) {
                    Thread.onSpinWait();
                }
            });
            assertFalse(closeFuture.isDone());
            assertThrows(
                    PersistenceClosedException.class,
                    () -> store.submitAsync(connection -> null));

            release.countDown();
            inFlight.toCompletableFuture().get(2, TimeUnit.SECONDS);
            queued.toCompletableFuture().get(2, TimeUnit.SECONDS);
            closeFuture.get(2, TimeUnit.SECONDS);

            assertTrue(queuedWorkRan.get());
            assertTrue(store.isClosed());
        } finally {
            release.countDown();
            if (!store.isClosed()) {
                store.close();
            }
            closerExecutor.shutdownNow();
            assertTrue(closerExecutor.awaitTermination(2, TimeUnit.SECONDS));
        }

        assertFalse(hasLiveThread("chunkland-persistence"));
    }

    @Test
    @Timeout(5)
    void failedInitializationClosesConnectionAndStopsExecutor() throws Exception {
        Path path = databasePath();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE schema_version (version TEXT NOT NULL)");
        }

        AtomicReference<Connection> openedConnection = new AtomicReference<>();
        assertThrows(
                PersistenceException.class,
                () -> PersistenceStore.open(
                        path,
                        databasePath -> {
                            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                            openedConnection.set(connection);
                            return connection;
                        }));

        assertNotNull(openedConnection.get());
        Connection connection = openedConnection.get();
        assertTrue(connection.isClosed());
        assertFalse(hasLiveThread("chunkland-persistence"));
    }

    @Test
    @Timeout(5)
    void errorDuringInitializationClosesOwnedConnectionAndStopsExecutor() throws Exception {
        AssertionError injectedFailure = new AssertionError("injected initialization failure");
        AtomicReference<Connection> openedConnection = new AtomicReference<>();
        AtomicReference<PersistenceStore> openedStore = new AtomicReference<>();

        try {
            AssertionError propagated = assertThrows(
                    AssertionError.class,
                    () -> PersistenceStore.open(
                            databasePath(),
                            path -> {
                                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                                openedConnection.set(connection);
                                return erroringConnection(connection, injectedFailure);
                            },
                            openedStore::set));

            assertSame(injectedFailure, propagated);
            assertNotNull(openedConnection.get());
            assertNotNull(openedStore.get());
            assertAll(
                    () -> assertTrue(openedConnection.get().isClosed()),
                    () -> assertTrue(openedStore.get().isClosed()),
                    () -> assertFalse(hasLiveThread("chunkland-persistence")));
        } finally {
            if (openedStore.get() != null && !openedStore.get().isClosed()) {
                openedStore.get().close();
            }
        }
    }

    private Path databasePath() {
        return temporaryDirectory.resolve("chunkland.db");
    }

    private int schemaVersionTableCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT COUNT(*) FROM sqlite_master "
                                + "WHERE type = 'table' AND name = 'schema_version'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private PersistenceThread persistenceThread() {
        Thread current = Thread.currentThread();
        return new PersistenceThread(current.threadId(), current.getName(), current.isDaemon());
    }

    private static PersistenceThread selectThreadAfterQuery(Connection connection) throws SQLException {
        selectOne(connection);
        Thread current = Thread.currentThread();
        return new PersistenceThread(current.threadId(), current.getName(), current.isDaemon());
    }

    private static void awaitRelease(CountDownLatch release) throws SQLException {
        try {
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new SQLException("test release latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("persistence test was interrupted", interrupted);
        }
    }

    private static Connection erroringConnection(Connection delegate, AssertionError failure) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("createStatement")) {
                                throw failure;
                            }
                            try {
                                return method.invoke(
                                        delegate,
                                        arguments == null ? new Object[0] : arguments);
                            } catch (InvocationTargetException invocationFailure) {
                                throw invocationFailure.getCause();
                            }
                        });
    }

    private static int selectOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean hasLiveThread(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    private record PersistenceThread(long id, String name, boolean daemon) {}
}
