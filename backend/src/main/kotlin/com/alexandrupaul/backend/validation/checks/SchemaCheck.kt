package com.alexandrupaul.backend.validation.checks

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.CheckCounts
import com.alexandrupaul.backend.validation.SchemaResult
import com.alexandrupaul.backend.validation.checks.schema.compareColumns
import com.alexandrupaul.backend.validation.checks.schema.compareConstraints
import com.alexandrupaul.backend.validation.checks.schema.compareForeignKeys
import com.alexandrupaul.backend.validation.checks.schema.compareIndexes
import com.alexandrupaul.backend.validation.checks.schema.comparePrimaryKeys
import com.alexandrupaul.backend.validation.checks.schema.compareSequences
import com.alexandrupaul.backend.validation.fetchOraPgTables
import java.sql.Connection

fun compareSchema(
    oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
    scope: List<String>, tableFilter: List<String>, errors: MutableList<Map<String, String>>,
    logLine: (String) -> Unit
): SchemaResult {
    val checks = mutableMapOf<String, CheckCounts>()
    val tableResults = mutableListOf<Map<String, Any>>()
    val sequenceResults = mutableListOf<Map<String, Any>>()

    val (oraTables, pgTables) = fetchOraPgTables(oraConn, pgConn, project, tableFilter, pgSchema)

    logLine("  Found ${oraTables.size} Oracle tables, ${pgTables.size} PostgreSQL tables")

    for (oracleTable in oraTables) {
        val pgTableName = oracleTable.lowercase()
        val tableEntry = mutableMapOf<String, Any>(
            "oracleTable" to oracleTable,
            "postgresTable" to pgTableName,
            "exists" to (pgTableName in pgTables)
        )

        if (pgTableName !in pgTables) {
            logLine("  [FAIL] Table $oracleTable — does not exist in PostgreSQL")
            checks.getOrPut("tables") { CheckCounts() }.let { it.total++; it.failed++ }
            errors.add(
                mapOf(
                    "table" to oracleTable,
                    "phase" to "schema_check",
                    "message" to "Table does not exist in PostgreSQL"
                )
            )
            tableResults.add(tableEntry)
            continue
        }

        checks.getOrPut("tables") { CheckCounts() }.let { it.total++; it.passed++ }
        logLine("  [PASS] Table $oracleTable — exists in PostgreSQL")

        try {
            if ("TABLES" in scope) {
                val columnResult = compareColumns(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                tableEntry["columns"] = columnResult.columns
                checks.getOrPut("columns") { CheckCounts() }.let {
                    it.total += columnResult.total; it.passed += columnResult.passed
                    it.failed += columnResult.failed; it.skipped += columnResult.skipped
                }
            }

            if ("PKS" in scope) {
                val pkResult = comparePrimaryKeys(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                tableEntry["primaryKey"] = pkResult.detail
                checks.getOrPut("primaryKeys") { CheckCounts() }.let {
                    it.total++
                    if (pkResult.passed) it.passed++ else it.failed++
                }
            }

            if ("FKS" in scope) {
                val fkResult = compareForeignKeys(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                tableEntry["foreignKeys"] = fkResult.details
                checks.getOrPut("foreignKeys") { CheckCounts() }.let {
                    it.total += fkResult.total; it.passed += fkResult.passed; it.failed += fkResult.failed
                }
            }

            if ("INDEXES" in scope) {
                val idxResult = compareIndexes(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                tableEntry["indexes"] = idxResult.details
                checks.getOrPut("indexes") { CheckCounts() }.let {
                    it.total += idxResult.total; it.passed += idxResult.passed; it.failed += idxResult.failed
                }
            }

            if ("CONSTRAINTS" in scope) {
                val conResult = compareConstraints(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                tableEntry["checkConstraints"] = conResult.details
                checks.getOrPut("constraints") { CheckCounts() }.let {
                    it.total += conResult.total; it.passed += conResult.passed; it.failed += conResult.failed
                }
            }

        } catch (e: Exception) {
            logLine("  [ERROR] Table $oracleTable — ${e.message}")
            errors.add(
                mapOf(
                    "table" to oracleTable,
                    "phase" to "schema_check",
                    "message" to (e.message ?: "Unknown error")
                )
            )
        }

        tableResults.add(tableEntry)
    }

    if ("SEQUENCES" in scope) {
        logLine("  Comparing sequences...")
        try {
            val seqResult = compareSequences(oraConn, pgConn, project, pgSchema, logLine)
            sequenceResults.addAll(seqResult.details)
            checks.getOrPut("sequences") { CheckCounts() }.let {
                it.total += seqResult.total; it.passed += seqResult.passed; it.failed += seqResult.failed
            }
        } catch (e: Exception) {
            logLine("  [ERROR] Sequence comparison — ${e.message}")
            errors.add(mapOf("table" to "*", "phase" to "sequence_check", "message" to (e.message ?: "Unknown error")))
        }
    }

    return SchemaResult(tableResults, sequenceResults, checks)
}
