package com.alexandrupaul.backend.validation

import com.alexandrupaul.backend.project.Project
import java.sql.Connection
import kotlin.use

fun queryScalar(conn: Connection, sql: String): String {
    conn.createStatement().use { stmt ->
        stmt.queryTimeout = 300
        stmt.executeQuery(sql).use { rs ->
            return if (rs.next()) rs.getString(1) ?: "NULL" else "NULL"
        }
    }
}

fun compareNumericValues(a: String, b: String): Boolean {
    if (a == b) return true
    return try {
        java.math.BigDecimal(a).compareTo(java.math.BigDecimal(b)) == 0
    } catch (_: Exception) {
        false
    }
}

fun shouldIncludeTable(tableName: String, project: Project, tableList: List<String>): Boolean {
    if (tableList.isEmpty()) return true
    val upperTable = tableName.uppercase()
    val upperList = tableList.map { it.uppercase() }
    return when (project.tableFilterMode) {
        "ALLOW" -> upperTable in upperList
        "EXCLUDE" -> upperTable !in upperList
        else -> true
    }
}

fun isTypeCompatible(oracleType: String, pgType: String): Boolean {
    val oraUpper = oracleType.uppercase().replace(Regex("""\(\d+[,\d]*\)"""), "").trim()
    val pgLower = pgType.lowercase()
    val compatibleTypes = TYPE_COMPATIBILITY[oraUpper] ?: return pgLower.contains(oraUpper.lowercase())
    return pgLower in compatibleTypes
}

fun fetchOraPgTables(
    oraConn: Connection,
    pgConn: Connection,
    project: Project,
    tableFilter: List<String>,
    pgSchema: String
): Pair<List<String>, Set<String>> {
    val oraTables = mutableListOf<String>()
    oraConn.prepareStatement("SELECT table_name FROM all_tables WHERE owner = UPPER(?) ORDER BY table_name")
        .use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val t = rs.getString("table_name")
                    if (shouldIncludeTable(t, project, tableFilter)) oraTables.add(t)
                }
            }
        }

    val pgTables = mutableSetOf<String>()
    pgConn.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'")
        .use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) pgTables.add(rs.getString("table_name").lowercase())
            }
        }

    return Pair(oraTables, pgTables)
}
