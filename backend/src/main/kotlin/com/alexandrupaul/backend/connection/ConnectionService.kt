package com.alexandrupaul.backend.connection

import com.alexandrupaul.backend.project.Project
import org.springframework.stereotype.Service
import java.sql.DriverManager

@Service
class ConnectionService {

    fun testOracle(p: Project): ConnectionResult {
        // Construct JDBC URL (Oracle Thin driver)
        val url = "jdbc:oracle:thin:@${p.oracleHost}:${p.oraclePort}/${p.oracleSid}"

        return try {
            // Try to connect (timeout after 5 seconds to avoid hanging)
            DriverManager.setLoginTimeout(5)
            DriverManager.getConnection(url, p.oracleUser, p.oraclePassword).use {
                ConnectionResult(true, "Oracle Connection Successful! (Version: ${it.metaData.databaseProductVersion})")
            }
        } catch (e: Exception) {
            // Gracefully catch error and return false
            ConnectionResult(false, "Oracle Connection Failed: ${e.message}")
        }
    }

    fun testPostgres(p: Project): ConnectionResult {
        // Construct JDBC URL (Postgres)
        val url = "jdbc:postgresql://${p.postgresHost}:${p.postgresPort}/${p.postgresDb}"

        return try {
            DriverManager.setLoginTimeout(5)
            DriverManager.getConnection(url, p.postgresUser, p.postgresPassword).use {
                ConnectionResult(true, "Postgres Connection Successful! (Version: ${it.metaData.databaseProductVersion})")
            }
        } catch (e: Exception) {
            // Gracefully catch error and return false
            ConnectionResult(false, "Postgres Connection Failed: ${e.message}")
        }
    }
}
