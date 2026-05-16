package com.alexandrupaul.backend.validation_run

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "validation_runs")
data class ValidationRun(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val projectId: Long,
    val startTime: LocalDateTime = LocalDateTime.now(),
    var endTime: LocalDateTime? = null,
    var status: String = "RUNNING",
    var logFileName: String = "",
    var reportFileName: String = "",
    @Column(length = 2000)
    var validationScope: String = "",
    var totalChecks: Int = 0,
    var passedChecks: Int = 0,
    var failedChecks: Int = 0,
    var skippedChecks: Int = 0
)
