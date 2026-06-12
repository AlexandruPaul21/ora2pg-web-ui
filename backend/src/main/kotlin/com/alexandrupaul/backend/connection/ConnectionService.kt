package com.alexandrupaul.backend.connection

import com.alexandrupaul.backend.project.Project
import org.springframework.stereotype.Service
import java.sql.DriverManager

@Service
class ConnectionService {

    fun buildOracleJdbcUrl(project: Project): String = when (project.oracleConnectionType) {
        "SERVICE_NAME" -> "jdbc:oracle:thin:@//${project.oracleHost}:${project.oraclePort}/${project.oracleSid}"
        "CUSTOM" -> project.oracleCustomDsn ?: ""
        else -> "jdbc:oracle:thin:@${project.oracleHost}:${project.oraclePort}:${project.oracleSid}"
    }

    fun testOracleConnection(project: Project): ConnectionResult {
        val url = buildOracleJdbcUrl(project)

        return try {
            DriverManager.getConnection(url, project.oracleUser, project.oraclePassword).use { connection ->
                if (connection.isValid(5)) {
                    ConnectionResult(true, "Successfully connected to Oracle!")
                } else {
                    ConnectionResult(false, "Connection failed: Timeout or invalid state.")
                }
            }
        } catch (e: Exception) {
            ConnectionResult(false, "Oracle Error: ${e.message}")
        }
    }

    fun buildPostgresJdbcUrl(project: Project): String {
        var url = "jdbc:postgresql://${project.postgresHost}:${project.postgresPort}/${project.postgresDb}?sslmode=${project.postgresSslMode}"
        if (!project.postgresSearchPath.isNullOrBlank()) {
            url += "&currentSchema=${project.postgresSearchPath}"
        }
        return url
    }

    fun testPostgresConnection(project: Project): ConnectionResult {
        val url = buildPostgresJdbcUrl(project)

        return try {
            DriverManager.getConnection(url, project.postgresUser, project.postgresPassword).use { connection ->
                if (connection.isValid(5)) {
                    ConnectionResult(true, "Successfully connected to PostgreSQL!")
                } else {
                    ConnectionResult(false, "Connection failed: Timeout or invalid state.")
                }
            }
        } catch (e: Exception) {
            ConnectionResult(false, "Postgres Error: ${e.message}")
        }
    }

    fun getPostgresSchema(project: Project): String {
        return if (!project.postgresSearchPath.isNullOrBlank()) project.postgresSearchPath!! else "public"
    }

    fun fetchOracleTables(project: Project): List<String> {
        val url = buildOracleJdbcUrl(project)
        val tables = mutableListOf<String>()
        DriverManager.getConnection(url, project.oracleUser, project.oraclePassword).use { conn ->
            conn.prepareStatement("SELECT table_name FROM all_tables WHERE owner = UPPER(?) ORDER BY table_name").use { stmt ->
                stmt.setString(1, project.oracleUser)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tables.add(rs.getString("table_name"))
                    }
                }
            }
        }
        return tables
    }
}
