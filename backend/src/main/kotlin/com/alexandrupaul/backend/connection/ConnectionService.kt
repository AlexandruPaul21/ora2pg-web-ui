package com.alexandrupaul.backend.connection

import com.alexandrupaul.backend.project.Project
import org.springframework.stereotype.Service
import java.sql.DriverManager

@Service
class ConnectionService {

    fun testOracleConnection(project: Project): ConnectionResult {
        // Build the correct JDBC URL based on the connection type
        val url = when (project.oracleConnectionType) {
            "SERVICE_NAME" -> "jdbc:oracle:thin:@//${project.oracleHost}:${project.oraclePort}/${project.oracleSid}"
            "CUSTOM" -> project.oracleCustomDsn ?: "" // Use custom DSN if provided
            else -> "jdbc:oracle:thin:@${project.oracleHost}:${project.oraclePort}:${project.oracleSid}" // Default SID
        }

        return try {
            DriverManager.getConnection(url, project.oracleUser, project.oraclePassword).use { connection ->
                if (connection.isValid(5)) {
                    ConnectionResult(true, "Successfully connected to Oracle!")
                } else {
                    ConnectionResult(false, "Connection failed: Timeout or invalid state.")
                }
            }
        } catch (e: Exception) {
            // Return the exception message safely to the frontend
            ConnectionResult(false, "Oracle Error: ${e.message}")
        }
    }

    fun testPostgresConnection(project: Project): ConnectionResult {
        // Build the Postgres URL with SSL Mode
        var url = "jdbc:postgresql://${project.postgresHost}:${project.postgresPort}/${project.postgresDb}?sslmode=${project.postgresSslMode}"

        // Append search path if the user provided one
        if (!project.postgresSearchPath.isNullOrBlank()) {
            url += "&currentSchema=${project.postgresSearchPath}"
        }

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
}
