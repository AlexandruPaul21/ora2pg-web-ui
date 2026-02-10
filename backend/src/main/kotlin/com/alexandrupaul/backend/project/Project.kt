package com.alexandrupaul.backend.project

import com.alexandrupaul.backend.security.AttributeEncryptor
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "projects")
data class Project(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,

    // --- Oracle Source ---
    var oracleHost: String = "localhost",
    var oraclePort: Int = 1521,
    var oracleSid: String = "ORCLCDB",
    var oracleUser: String = "system",

    @Convert(converter = AttributeEncryptor::class) // <--- Encrypted!
    @Column(name = "oracle_password")
    var oraclePassword: String = "",

    // --- Postgres Destination ---
    var postgresHost: String = "localhost",
    var postgresPort: Int = 5432,
    var postgresDb: String = "postgres",
    var postgresUser: String = "postgres",

    @Convert(converter = AttributeEncryptor::class) // <--- Encrypted!
    @Column(name = "postgres_password")
    var postgresPassword: String = "",

    // --- Metadata ---
    var createdAt: LocalDateTime = LocalDateTime.now(),

    // For storing custom Ora2Pg configuration directives (e.g. TYPE TABLE, COPY, etc.)
    @Column(columnDefinition = "TEXT")
    var ora2pgConfig: String = ""
)
