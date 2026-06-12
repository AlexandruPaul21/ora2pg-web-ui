package com.alexandrupaul.backend.validation.checks.schema

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.PKResult
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.use

fun comparePrimaryKeys(
    oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
    project: Project, logLine: (String) -> Unit
): PKResult {
    val oraColumns = oraConn.prepareStatement(ORACLE_PK_QUERY).use { stmt ->
        stmt.setString(1, project.oracleUser)
        stmt.setString(2, oracleTable)
        extractPrimaryKeyColumns(stmt)
    }

    val pgColumns = pgConn.prepareStatement(POSTGRES_PK_QUERY).use { stmt ->
        stmt.setString(1, pgSchema)
        stmt.setString(2, oracleTable.lowercase())
        extractPrimaryKeyColumns(stmt)
    }

    val oraUpper = oraColumns.map { it.uppercase() }
    val pgUpper = pgColumns.map { it.uppercase() }
    val match = oraUpper == pgUpper

    val status = when {
        oraColumns.isEmpty() && pgColumns.isEmpty() -> { logLine("    [PASS] PK $oracleTable — no PK defined (both sides)"); "PASS" }
        match -> { logLine("    [PASS] PK $oracleTable — columns match: ${oraColumns.joinToString(", ")}"); "PASS" }
        else -> { logLine("    [FAIL] PK $oracleTable — Oracle=${oraColumns.joinToString(",")}, PG=${pgColumns.joinToString(",")}"); "FAIL" }
    }

    return PKResult(
        mapOf("status" to status, "oracleColumns" to oraColumns, "postgresColumns" to pgColumns),
        status == "PASS"
    )
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_CONSTRAINTS.html
private const val ORACLE_PK_QUERY = """
    SELECT acc.column_name FROM all_constraints ac
    JOIN all_cons_columns acc ON ac.constraint_name = acc.constraint_name AND ac.owner = acc.owner
    WHERE ac.owner = UPPER(?) AND ac.table_name = ? AND ac.constraint_type = 'P'
    ORDER BY acc.position
"""

// [DOC]: https://www.postgresql.org/docs/current/infoschema-table-constraints.html
private const val POSTGRES_PK_QUERY = """
    SELECT kcu.column_name FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY'
    ORDER BY kcu.ordinal_position
"""

private fun extractPrimaryKeyColumns(stmt: PreparedStatement): List<String> {
    val columns = mutableListOf<String>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            columns.add(rs.getString("column_name"))
        }
    }
    return columns
}
