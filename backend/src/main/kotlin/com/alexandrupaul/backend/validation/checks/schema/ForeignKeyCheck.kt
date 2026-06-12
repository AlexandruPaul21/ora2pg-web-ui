package com.alexandrupaul.backend.validation.checks.schema

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.FKResult
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.collections.iterator
import kotlin.use

private fun extractForeignKeys(stmt: PreparedStatement): MutableMap<String, MutableMap<String, Any>> {
    val fks = mutableMapOf<String, MutableMap<String, Any>>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            val name = rs.getString("constraint_name")
            val fk = fks.getOrPut(name) {
                mutableMapOf(
                    "columns" to mutableListOf<String>(),
                    "refTable" to "",
                    "refColumns" to mutableListOf<String>()
                )
            }
            @Suppress("UNCHECKED_CAST")
            (fk["columns"] as MutableList<String>).add(rs.getString("column_name"))
            fk["refTable"] = rs.getString("ref_table")
            @Suppress("UNCHECKED_CAST")
            (fk["refColumns"] as MutableList<String>).add(rs.getString("ref_column"))
        }
    }
    return fks
}

fun compareForeignKeys(
    oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
    project: Project, logLine: (String) -> Unit
): FKResult {

    val oraFKs = oraConn.prepareStatement(ORACLE_FK_QUERY).use { stmt ->
        stmt.setString(1, project.oracleUser)
        stmt.setString(2, oracleTable)
        extractForeignKeys(stmt)
    }

    val pgFKs = pgConn.prepareStatement(POSTGRES_FK_QUERY).use { stmt ->
        stmt.setString(1, pgSchema)
        stmt.setString(2, oracleTable.lowercase())
        extractForeignKeys(stmt)
    }

    var total = 0
    var passed = 0
    var failed = 0
    val details = mutableListOf<Map<String, Any>>()

    for ((name, oraFK) in oraFKs) {
        total++
        @Suppress("UNCHECKED_CAST")
        val oraColsUpper = (oraFK["columns"] as List<String>).map { it.uppercase() }
        val oraRefTable = (oraFK["refTable"] as String).uppercase()

        @Suppress("UNCHECKED_CAST")
        val oraRefColsUpper = (oraFK["refColumns"] as List<String>).map { it.uppercase() }

        val matchingPgFK = pgFKs.values.find { pgFK ->
            @Suppress("UNCHECKED_CAST")
            val pgCols = (pgFK["columns"] as List<String>).map { it.uppercase() }
            val pgRefTable = (pgFK["refTable"] as String).uppercase()

            @Suppress("UNCHECKED_CAST")
            val pgRefCols = (pgFK["refColumns"] as List<String>).map { it.uppercase() }
            pgCols == oraColsUpper && pgRefTable == oraRefTable && pgRefCols == oraRefColsUpper
        }

        if (matchingPgFK != null) {
            passed++
            logLine("    [PASS] FK $name on $oracleTable")
            details.add(
                mapOf(
                    "name" to name, "status" to "PASS",
                    "oracleDefinition" to "$oracleTable(${oraColsUpper.joinToString(",")}) -> $oraRefTable(${
                        oraRefColsUpper.joinToString(",")
                    })",
                    "postgresDefinition" to "matched"
                )
            )
        } else {
            failed++
            logLine("    [FAIL] FK $name on $oracleTable — not found in PostgreSQL")
            details.add(
                mapOf(
                    "name" to name, "status" to "FAIL",
                    "oracleDefinition" to "$oracleTable(${oraColsUpper.joinToString(",")}) -> $oraRefTable(${
                        oraRefColsUpper.joinToString(",")
                    })",
                    "postgresDefinition" to "MISSING"
                )
            )
        }
    }

    if (oraFKs.isEmpty()) {
        logLine("    [PASS] No foreign keys on $oracleTable (both sides)")
    }

    return FKResult(details, total, passed, failed)
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_CONSTRAINTS.html
private const val ORACLE_FK_QUERY = """
    SELECT ac.constraint_name, acc.column_name, ac_r.table_name AS ref_table, acc_r.column_name AS ref_column
    FROM all_constraints ac
    JOIN all_cons_columns acc ON ac.constraint_name = acc.constraint_name AND ac.owner = acc.owner
    JOIN all_constraints ac_r ON ac.r_constraint_name = ac_r.constraint_name AND ac.r_owner = ac_r.owner
    JOIN all_cons_columns acc_r ON ac_r.constraint_name = acc_r.constraint_name AND ac_r.owner = acc_r.owner AND acc.position = acc_r.position
    WHERE ac.owner = UPPER(?) AND ac.table_name = ? AND ac.constraint_type = 'R'
    ORDER BY ac.constraint_name, acc.position
"""

// [DOC]: https://www.postgresql.org/docs/current/infoschema-table-constraints.html
private const val POSTGRES_FK_QUERY = """
    SELECT tc.constraint_name, kcu.column_name, ccu.table_name AS ref_table, ccu.column_name AS ref_column
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema
    WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'FOREIGN KEY'
    ORDER BY tc.constraint_name, kcu.ordinal_position
"""
