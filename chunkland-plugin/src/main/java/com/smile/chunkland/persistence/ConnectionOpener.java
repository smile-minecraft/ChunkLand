package com.smile.chunkland.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface ConnectionOpener {

    Connection open(Path databasePath) throws SQLException;
}
