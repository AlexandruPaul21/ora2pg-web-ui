package com.alexandrupaul.backend.migration_run

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "migration_runs")
data class MigrationRun(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val projectId: Long,

    val startTime: LocalDateTime = LocalDateTime.now(),
    var endTime: LocalDateTime? = null,

    var status: String = "RUNNING", // RUNNING, SUCCESS, SUCCESS_WITH_WARNING, FAILED
    var logFileName: String = ""
)
