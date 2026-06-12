package com.alexandrupaul.backend.project

import com.alexandrupaul.backend.security.AttributeEncryptor
import jakarta.persistence.*

@Entity
@Table(name = "projects")
data class Project(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    var name: String = "",

    // --- Oracle Basic ---
    var oracleHost: String = "localhost",
    var oraclePort: Int = 1521,
    var oracleSid: String = "XE",
    var oracleUser: String = "",

    @Convert(converter = AttributeEncryptor::class)
    @Column(name = "oracle_password")
    var oraclePassword: String = "",

    // --- Oracle Advanced ---
    var oracleConnectionType: String = "SID", // Can be "SID", "SERVICE_NAME", or "CUSTOM"
    var oracleCustomDsn: String? = "", // For completely custom connection strings

    // --- Postgres Basic ---
    var postgresHost: String = "localhost",
    var postgresPort: Int = 5432,
    var postgresDb: String = "postgres",
    var postgresUser: String = "",

    @Convert(converter = AttributeEncryptor::class)
    @Column(name = "postgres_password")
    var postgresPassword: String = "",

    // --- Postgres Advanced ---
    var postgresSslMode: String = "disable", // "disable", "require", "verify-ca", etc.
    var postgresSearchPath: String? = "", // Specific schemas to target

    // --- Table Scope ---
    var tableFilterMode: String? = "", // "ALLOW", "EXCLUDE", or "" (no filter)
    @Column(length = 4000)
    var selectedTables: String = "",

    @Column(length = 2000)
    var ora2pgConfig: String = ""
) {
    fun getTableList(): List<String> {
        if (tableFilterMode.isNullOrBlank() || selectedTables.isBlank()) {
            return emptyList()
        }
        return selectedTables.split(" ").filter { it.isNotBlank() }
    }
}
