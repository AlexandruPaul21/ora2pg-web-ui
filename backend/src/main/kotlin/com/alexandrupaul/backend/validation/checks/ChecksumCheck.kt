package com.alexandrupaul.backend.validation.checks

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.ChecksumResults
import com.alexandrupaul.backend.validation.compareNumericValues
import com.alexandrupaul.backend.validation.fetchOraPgTables
import com.alexandrupaul.backend.validation.queryScalar
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.use

fun compareChecksums(
    oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
    tableFilter: List<String>, errors: MutableList<Map<String, String>>,
    logLine: (String) -> Unit
): ChecksumResults {
    var total = 0
    var passed = 0
    var failed = 0
    var skipped = 0
    val tableChecksums = mutableMapOf<String, List<Map<String, Any>>>()

    val (oraTables, pgTables) = fetchOraPgTables(oraConn, pgConn, project, tableFilter, pgSchema)

    for (oracleTable in oraTables) {
        if (oracleTable.lowercase() !in pgTables) continue

        logLine("  Check sums for table $oracleTable...")
        val columnChecksums = mutableListOf<Map<String, Any>>()

        try {
            val columns = oraConn.prepareStatement(ORACLE_COLUMN_TYPES_QUERY).use { stmt ->
                stmt.setString(1, project.oracleUser)
                stmt.setString(2, oracleTable)
                extractColumnsAndTypes(stmt)
            }

            for ((colName, colType) in columns) {
                val upperType = colType.uppercase()
                try {
                    when {
                        upperType in NUMERIC_TYPES || upperType.startsWith("NUMBER") -> {
                            total++
                            val oraSum = queryScalar(oraConn, "SELECT COALESCE(SUM(\"$colName\"), 0) FROM \"$oracleTable\"")
                            val pgSum = queryScalar(pgConn, "SELECT COALESCE(SUM(\"${colName.lowercase()}\"), 0) FROM \"$pgSchema\".\"${oracleTable.lowercase()}\"")
                            val match = compareNumericValues(oraSum, pgSum)

                            if (match) passed++ else failed++
                            val status = if (match) "PASS" else "FAIL"

                            logLine("    [$status] $oracleTable.$colName SUM: Oracle=$oraSum, PG=$pgSum")
                            columnChecksums.add(
                                mapOf(
                                    "column" to colName, "type" to "numeric", "method" to "SUM",
                                    "status" to status, "oracleValue" to oraSum, "postgresValue" to pgSum
                                )
                            )
                        }

                        upperType in STRING_TYPES || upperType in DATE_TYPES -> {
                            total++
                            val typeLabel = if (upperType in STRING_TYPES) "string" else "date"

                            val oraCount = queryScalar(oraConn, "SELECT COUNT(DISTINCT \"$colName\") FROM \"$oracleTable\"")
                            val pgCount = queryScalar(pgConn, "SELECT COUNT(DISTINCT \"${colName.lowercase()}\") FROM \"$pgSchema\".\"${oracleTable.lowercase()}\"")
                            val match = oraCount == pgCount

                            if (match) passed++ else failed++
                            val status = if (match) "PASS" else "FAIL"

                            logLine("    [$status] $oracleTable.$colName COUNT_DISTINCT: Oracle=$oraCount, PG=$pgCount")
                            columnChecksums.add(
                                mapOf(
                                    "column" to colName, "type" to typeLabel, "method" to "COUNT_DISTINCT",
                                    "status" to status, "oracleValue" to oraCount, "postgresValue" to pgCount
                                )
                            )
                        }

                        else -> {
                            skipped++
                            total++
                            columnChecksums.add(
                                mapOf(
                                    "column" to colName, "type" to upperType, "method" to "SKIPPED",
                                    "status" to "SKIPPED", "oracleValue" to "", "postgresValue" to ""
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    skipped++
                    total++
                    logLine("    [SKIP] $oracleTable.$colName — ${e.message}")
                    columnChecksums.add(
                        mapOf(
                            "column" to colName, "type" to upperType, "method" to "ERROR",
                            "status" to "SKIPPED", "oracleValue" to "", "postgresValue" to (e.message ?: "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logLine("  [ERROR] Table $oracleTable — ${e.message}")
            errors.add(
                mapOf(
                    "table" to oracleTable,
                    "phase" to "data_checksum",
                    "message" to (e.message ?: "Unknown error")
                )
            )
        }

        if (columnChecksums.isNotEmpty()) {
            tableChecksums[oracleTable] = columnChecksums
        }
    }

    return ChecksumResults(total, passed, failed, skipped, tableChecksums)
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_TAB_COLUMNS.html
private const val ORACLE_COLUMN_TYPES_QUERY = """
    SELECT column_name, data_type FROM all_tab_columns
    WHERE owner = UPPER(?) AND table_name = ? ORDER BY column_id
"""

private val NUMERIC_TYPES = setOf("NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE", "INTEGER")
private val STRING_TYPES = setOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "VARCHAR")
private val DATE_TYPES = setOf("DATE", "TIMESTAMP", "TIMESTAMP(6)")

private fun extractColumnsAndTypes(stmt: PreparedStatement): List<Pair<String, String>> {
    val columns = mutableListOf<Pair<String, String>>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            columns.add(rs.getString("column_name") to rs.getString("data_type"))
        }
    }
    return columns
}
