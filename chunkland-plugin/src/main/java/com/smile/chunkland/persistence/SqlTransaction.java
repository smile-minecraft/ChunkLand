package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** Small transaction boundary used only by persistence-thread callbacks. */
final class SqlTransaction {

    private SqlTransaction() {
    }

    static <T> T run(Connection connection, SqlWork<T> work) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            throw new SQLException("a ledger transaction cannot be nested in an active transaction");
        }
        boolean committed = false;
        Throwable primary = null;
        T result = null;
        connection.setAutoCommit(false);
        try {
            result = work.run(connection);
            connection.commit();
            committed = true;
        } catch (Throwable failure) {
            primary = failure;
            try {
                connection.rollback();
            } catch (Throwable rollbackFailure) {
                primary.addSuppressed(rollbackFailure);
            }
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (Throwable restoreFailure) {
                if (primary != null) {
                    primary.addSuppressed(restoreFailure);
                } else {
                    primary = restoreFailure;
                }
            }
        }
        if (primary != null) {
            if (primary instanceof SQLException failure) throw failure;
            if (primary instanceof RuntimeException failure) throw failure;
            if (primary instanceof Error failure) throw failure;
            throw new SQLException("ledger transaction failed", primary);
        }
        if (!committed) {
            throw new SQLException("ledger transaction did not commit");
        }
        return result;
    }
}
