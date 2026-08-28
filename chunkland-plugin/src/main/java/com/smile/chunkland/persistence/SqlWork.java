package com.smile.chunkland.persistence;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface SqlWork<T> {

    T run(Connection connection) throws SQLException;
}
