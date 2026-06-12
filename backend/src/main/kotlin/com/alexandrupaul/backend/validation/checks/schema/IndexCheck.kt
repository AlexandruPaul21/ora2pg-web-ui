package com.alexandrupaul.backend.validation.checks.schema

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.IndexResult
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.collections.iterator
import kotlin.use

fun compareIndexes(
    oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
    project: Project, logLine: (String) -> Unit
): IndexResult {
    val oraIndexes = oraConn.prepareStatement(ORACLE_INDEXES_QUERY).use { stmt ->
        stmt.setString(1, project.oracleUser)
        stmt.setString(2, oracleTable)
        extractIndexes(stmt) { rs -> rs.getString("uniqueness") == "UNIQUE" }
    }

    val pgIndexes = pgConn.prepareStatement(POSTGRES_INDEXES_QUERY).use { stmt ->
        stmt.setString(1, pgSchema)
        stmt.setString(2, oracleTable.lowercase())
        extractIndexes(stmt) { rs -> rs.getBoolean("indisunique") }
    }

    var total = 0
    var passed = 0
    var failed = 0
    val details = mutableListOf<Map<String, Any>>()

    for ((name, oraIdx) in oraIndexes) {
        total++
        @Suppress("UNCHECKED_CAST")
        val oraColsUpper = (oraIdx["columns"] as List<String>).map { it.uppercase() }

        val matchingPgIdx = pgIndexes.values.find { pgIdx ->
            @Suppress("UNCHECKED_CAST")
            val pgCols = (pgIdx["columns"] as List<String>).map { it.uppercase() }
            pgCols == oraColsUpper
        }

        if (matchingPgIdx != null) {
            passed++
            logLine("    [PASS] Index $name on $oracleTable (${oraColsUpper.joinToString(",")})")
        } else {
            failed++
            logLine("    [FAIL] Index $name on $oracleTable — no matching index in PostgreSQL")
        }

        details.add(mapOf(
            "name" to name,
            "status" to if (matchingPgIdx != null) "PASS" else "FAIL",
            "oracleColumns" to oraColsUpper,
            "postgresColumns" to (matchingPgIdx?.let {
                @Suppress("UNCHECKED_CAST")
                (it["columns"] as List<String>)
            } ?: emptyList<String>()),
            "unique" to (oraIdx["unique"] ?: false)
        ))
    }

    if (oraIndexes.isEmpty()) {
        logLine("    [PASS] No indexes on $oracleTable")
    }

    return IndexResult(details, total, passed, failed)
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_INDEXES.html
private const val ORACLE_INDEXES_QUERY = """
    SELECT ai.index_name, ai.uniqueness, aic.column_name
    FROM all_indexes ai
    JOIN all_ind_columns aic ON ai.index_name = aic.index_name AND ai.owner = aic.index_owner
    WHERE ai.owner = UPPER(?) AND ai.table_name = ?
      AND ai.index_type IN ('NORMAL', 'BITMAP', 'FUNCTION-BASED NORMAL')
      AND ai.index_name NOT IN (
          SELECT constraint_name FROM all_constraints
          WHERE owner = ai.owner AND table_name = ai.table_name
            AND constraint_type IN ('P', 'U')
      )
    ORDER BY ai.index_name, aic.column_position
"""

// [DOC]: https://www.postgresql.org/docs/current/catalog-pg-index.html
private const val POSTGRES_INDEXES_QUERY = """
    SELECT i.relname AS index_name, ix.indisunique, a.attname AS column_name
    FROM pg_index ix
    JOIN pg_class t ON ix.indrelid = t.oid
    JOIN pg_class i ON ix.indexrelid = i.oid
    JOIN pg_namespace n ON t.relnamespace = n.oid
    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
    WHERE n.nspname = ? AND t.relname = ? AND NOT ix.indisprimary
    ORDER BY i.relname, array_position(ix.indkey, a.attnum)
"""

private fun extractIndexes(
    stmt: PreparedStatement,
    uniquenessResolver: (ResultSet) -> Boolean
): MutableMap<String, MutableMap<String, Any>> {
    val indexes = mutableMapOf<String, MutableMap<String, Any>>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            val name = rs.getString("index_name")
            val idx = indexes.getOrPut(name) {
                mutableMapOf("unique" to false, "columns" to mutableListOf<String>())
            }
            idx["unique"] = uniquenessResolver(rs)

            @Suppress("UNCHECKED_CAST")
            (idx["columns"] as MutableList<String>).add(rs.getString("column_name"))
        }
    }
    return indexes
}
