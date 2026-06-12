package com.alexandrupaul.backend.validation.checks

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.fetchOraPgTables
import com.alexandrupaul.backend.validation.queryScalar
import java.sql.Connection

fun compareRowCounts(
    oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
    tableFilter: List<String>, errors: MutableList<Map<String, String>>,
    logLine: (String) -> Unit
): Map<String, Map<String, Any>> {
    val results = mutableMapOf<String, Map<String, Any>>()

    val (oraTables, pgTables) = fetchOraPgTables(oraConn, pgConn, project, tableFilter, pgSchema)

    for (oracleTable in oraTables) {
        val pgTableName = oracleTable.lowercase()
        if (pgTableName !in pgTables) {
            logLine("  [FAIL] $oracleTable — table does not exist in PostgreSQL")
            results[oracleTable] = mapOf("status" to "FAIL", "oracleCount" to 0L, "postgresCount" to 0L)
            errors.add(
                mapOf(
                    "table" to oracleTable,
                    "phase" to "row_count",
                    "message" to "Table does not exist in PostgreSQL"
                )
            )
            continue
        }

        try {
            val oracleCount = queryScalar(oraConn, "SELECT COUNT(*) FROM \"$oracleTable\"").toLong()
            val pgCount = queryScalar(pgConn, "SELECT COUNT(*) FROM \"${pgSchema}\".\"${pgTableName}\"").toLong()
            val status = if (oracleCount == pgCount) "PASS" else "FAIL"
            logLine("  [${status}] $oracleTable — Oracle: $oracleCount, PostgreSQL: $pgCount")
            results[oracleTable] = mapOf("status" to status, "oracleCount" to oracleCount, "postgresCount" to pgCount)
        } catch (e: Exception) {
            logLine("  [ERROR] $oracleTable — ${e.message}")
            errors.add(
                mapOf(
                    "table" to oracleTable,
                    "phase" to "row_count",
                    "message" to (e.message ?: "Unknown error")
                )
            )
        }
    }

    return results
}
