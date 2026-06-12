package com.alexandrupaul.backend.validation.checks.schema

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.ConstraintResult
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.use

fun compareConstraints(
    oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
    project: Project, logLine: (String) -> Unit
): ConstraintResult {
    val oraConstraints = oraConn.prepareStatement(ORACLE_CONSTRAINTS_QUERY).use { stmt ->
        stmt.setString(1, project.oracleUser)
        stmt.setString(2, oracleTable)
        extractConstraints(stmt, "search_condition")
    }

    val pgConstraints = pgConn.prepareStatement(POSTGRES_CONSTRAINTS_QUERY).use { stmt ->
        stmt.setString(1, pgSchema)
        stmt.setString(2, oracleTable.lowercase())
        extractConstraints(stmt, "check_clause")
    }

    var total = 0
    var passed = 0
    var failed = 0
    val details = mutableListOf<Map<String, Any>>()

    val oraCount = oraConstraints.size
    val pgCount = pgConstraints.size
    total++
    if (oraCount == pgCount) {
        passed++
        logLine("    [PASS] Check constraints on $oracleTable — count matches ($oraCount)")
    } else {
        failed++
        logLine("    [FAIL] Check constraints on $oracleTable — Oracle=$oraCount, PG=$pgCount")
    }

    details.add(
        mapOf(
            "status" to if (oraCount == pgCount) "PASS" else "FAIL",
            "oracleCount" to oraCount,
            "postgresCount" to pgCount
        )
    )

    return ConstraintResult(details, total, passed, failed)
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_CONSTRAINTS.html
private const val ORACLE_CONSTRAINTS_QUERY = """
    SELECT constraint_name, search_condition FROM all_constraints
    WHERE owner = UPPER(?) AND table_name = ? AND constraint_type = 'C'
      AND constraint_name NOT LIKE 'SYS_%'
"""

// [DOC]: https://www.postgresql.org/docs/current/infoschema-check-constraints.html
private const val POSTGRES_CONSTRAINTS_QUERY = """
    SELECT tc.constraint_name, cc.check_clause
    FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc ON tc.constraint_name = cc.constraint_name AND tc.constraint_schema = cc.constraint_schema
    WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'CHECK'
      AND tc.constraint_name NOT LIKE '%_not_null'
"""

private fun extractConstraints(stmt: PreparedStatement, conditionColumn: String): Map<String, String> {
    val constraints = mutableMapOf<String, String>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            val name = rs.getString("constraint_name")
            val condition = rs.getString(conditionColumn) ?: ""
            constraints[name] = condition
        }
    }
    return constraints
}
