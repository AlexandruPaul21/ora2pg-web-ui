package com.alexandrupaul.backend.validation.checks.schema

import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.validation.SequenceResult
import java.sql.Connection
import java.sql.PreparedStatement
import kotlin.use

fun compareSequences(
    oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
    logLine: (String) -> Unit
): SequenceResult {
    val oraSequences = oraConn.prepareStatement(ORACLE_SEQUENCES_QUERY).use { stmt ->
        stmt.setString(1, project.oracleUser)
        extractSequenceNames(stmt)
    }

    val pgSequences = pgConn.prepareStatement(POSTGRES_SEQUENCES_QUERY).use { stmt ->
        stmt.setString(1, pgSchema)
        extractSequenceNames(stmt).map { it.lowercase() }.toSet()
    }

    var total = 0
    var passed = 0
    var failed = 0
    val details = mutableListOf<Map<String, Any>>()

    for (seq in oraSequences) {
        total++
        val found = seq.lowercase() in pgSequences

        if (found) {
            passed++
            logLine("    [PASS] Sequence $seq")
        } else {
            failed++
            logLine("    [FAIL] Sequence $seq — not found in PostgreSQL")
        }

        details.add(mapOf(
            "name" to seq,
            "status" to if (found) "PASS" else "FAIL",
            "oracleExists" to true,
            "postgresExists" to found,
            "postgresName" to seq.lowercase()
        ))
    }

    if (oraSequences.isEmpty()) {
        logLine("    [PASS] No sequences defined in Oracle")
    }

    return SequenceResult(details, total, passed, failed)
}

// [DOC]: https://docs.oracle.com/en/database/oracle/oracle-database/19/refrn/ALL_SEQUENCES.html
private const val ORACLE_SEQUENCES_QUERY = """
    SELECT sequence_name FROM all_sequences 
    WHERE sequence_owner = UPPER(?) AND sequence_name NOT LIKE 'ISEQ%'
"""

// [DOC]: https://www.postgresql.org/docs/current/infoschema-sequences.html
private const val POSTGRES_SEQUENCES_QUERY = """
    SELECT sequence_name FROM information_schema.sequences 
    WHERE sequence_schema = ?
"""

private fun extractSequenceNames(stmt: PreparedStatement): List<String> {
    val sequences = mutableListOf<String>()
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            sequences.add(rs.getString("sequence_name"))
        }
    }
    return sequences
}
