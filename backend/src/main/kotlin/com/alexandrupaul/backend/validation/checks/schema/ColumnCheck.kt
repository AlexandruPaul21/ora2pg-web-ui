package com.alexandrupaul.backend.validation.checks.schema

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.ColumnResult
import com.alexandrupaul.backend.validation.isTypeCompatible
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.use

fun compareColumns(
    oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
    project: Project, logLine: (String) -> Unit
): ColumnResult {
    val oracleColumns = oraConn.prepareStatement(ORACLE_COLUMNS_QUERY).use { stmt ->
        stmt.setString(1, project.oracleUser)
        stmt.setString(2, oracleTable)
        extractOracleColumns(stmt)
    }

    val pgColumns = pgConn.prepareStatement(POSTGRES_COLUMNS_QUERY).use { stmt ->
        stmt.setString(1, pgSchema)
        stmt.setString(2, oracleTable.lowercase())
        extractPostgresColumns(stmt)
    }

    var total = 0
    var passed = 0
    var failed = 0
    val skipped = 0
    val result = mutableListOf<Map<String, Any>>()

    for (oraCol in oracleColumns) {
        val colName = oraCol["name"] as String
        val pgCol = pgColumns[colName.lowercase()]
        total++

        if (pgCol == null) {
            failed++
            logLine("    [FAIL] Column $oracleTable.$colName — missing in PostgreSQL")
            result.add(
                mapOf(
                    "name" to colName, "oracleType" to (oraCol["type"] ?: ""), "postgresType" to "MISSING",
                    "pgColumnName" to "", "typeCompatible" to false, "nullableMatch" to false, "defaultMatch" to false
                )
            )
            continue
        }

        val oraType = (oraCol["type"] as? String)?.uppercase() ?: ""
        val pgType = (pgCol["type"] as? String)?.lowercase() ?: ""
        val typeCompatible = isTypeCompatible(oraType, pgType)

        val oraNullable = (oraCol["nullable"] as? String) == "Y"
        val pgNullable = (pgCol["nullable"] as? String) == "YES"
        val nullableMatch = oraNullable == pgNullable

        if (typeCompatible) {
            passed++
            logLine("    [PASS] Column $oracleTable.$colName ($oraType -> $pgType)")
        } else {
            failed++
            logLine("    [FAIL] Column $oracleTable.$colName — type mismatch: Oracle=$oraType, PG=$pgType")
        }

        result.add(
            mapOf(
                "name" to colName,
                "oracleType" to oraType,
                "postgresType" to pgType,
                "pgColumnName" to (pgCol["name"] ?: ""),
                "typeCompatible" to typeCompatible,
                "nullableMatch" to nullableMatch,
                "defaultMatch" to true,
                "oracleDefault" to (oraCol["default"] ?: ""),
                "postgresDefault" to (pgCol["default"] ?: "")
            )
        )
    }

    return ColumnResult(result, total, passed, failed, skipped)
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_TAB_COLUMNS.html
private const val ORACLE_COLUMNS_QUERY = """
    SELECT column_name, data_type, data_length, data_precision, data_scale, nullable, data_default
    FROM all_tab_columns WHERE owner = UPPER(?) AND table_name = ?
    ORDER BY column_id
"""

// [DOC]: https://www.postgresql.org/docs/current/infoschema-columns.html
private const val POSTGRES_COLUMNS_QUERY = """
    SELECT column_name, udt_name, character_maximum_length, numeric_precision, numeric_scale,
           is_nullable, column_default
    FROM information_schema.columns WHERE table_schema = ? AND table_name = ?
    ORDER BY ordinal_position
"""

private fun extractOracleColumns(stmt: PreparedStatement): List<Map<String, Any?>> {
    val columns = mutableListOf<Map<String, Any?>>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            columns.add(
                mapOf(
                    "name" to rs.getString("column_name"),
                    "type" to rs.getString("data_type"),
                    "length" to rs.getObject("data_length"),
                    "precision" to rs.getObject("data_precision"),
                    "scale" to rs.getObject("data_scale"),
                    "nullable" to rs.getString("nullable"),
                    "default" to rs.getString("data_default")?.trim()
                )
            )
        }
    }
    return columns
}

private fun extractPostgresColumns(stmt: PreparedStatement): Map<String, Map<String, Any?>> {
    val pgColumns = mutableMapOf<String, Map<String, Any?>>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            val name = rs.getString("column_name")
            pgColumns[name.lowercase()] = mapOf(
                "name" to name,
                "type" to rs.getString("udt_name"),
                "length" to rs.getObject("character_maximum_length"),
                "precision" to rs.getObject("numeric_precision"),
                "scale" to rs.getObject("numeric_scale"),
                "nullable" to rs.getString("is_nullable"),
                "default" to rs.getString("column_default")?.trim()
            )
        }
    }
    return pgColumns
}
