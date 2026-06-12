package com.alexandrupaul.backend.validation

import com.alexandrupaul.backend.connection.ConnectionService
import com.alexandrupaul.backend.project.ProjectRepository
import com.alexandrupaul.backend.validation.checks.compareChecksums
import com.alexandrupaul.backend.validation.checks.compareRowCounts
import com.alexandrupaul.backend.validation.checks.compareSchema
import com.alexandrupaul.backend.validation_run.ValidationRun
import com.alexandrupaul.backend.validation_run.ValidationRunRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.concurrent.Executors

@Service
class ValidationService(
    private val projectRepository: ProjectRepository,
    private val validationRunRepository: ValidationRunRepository,
    private val connectionService: ConnectionService,
) {

    private val executor = Executors.newCachedThreadPool()
    private val baseWorkDir = Paths.get("/data/projects")
    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun runValidation(projectId: Long, scope: List<String>): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)
        val project = projectRepository.findById(projectId).orElseThrow()

        var run = ValidationRun(projectId = projectId, validationScope = scope.joinToString(","))
        run = validationRunRepository.save(run)

        if (!Files.exists(baseWorkDir)) Files.createDirectories(baseWorkDir)
        val logFile = baseWorkDir.resolve("validation_run_${run.id}.log")
        run.logFileName = logFile.fileName.toString()
        run.reportFileName = "validation_report_${run.id}.json"
        validationRunRepository.save(run)

        executor.submit {
            logFile.toFile().printWriter().use { fileWriter ->
                var emitterClosed = false

                fun logLine(line: String) {
                    fileWriter.println(line)
                    fileWriter.flush()
                    if (!emitterClosed) {
                        try {
                            emitter.send(SseEmitter.event().name("log").data(line))
                        } catch (_: Exception) {
                            emitterClosed = true
                        }
                    }
                }

                try {
                    try {
                        emitter.send(SseEmitter.event().name("runId").data(run.id.toString()))
                    } catch (_: Exception) {
                        emitterClosed = true
                    }

                    val report = mutableMapOf<String, Any>(
                        "validationRunId" to run.id,
                        "projectId" to projectId,
                        "startTime" to run.startTime.toString(),
                    )
                    val schemaValidation = mutableMapOf<String, Any>()
                    val dataValidation = mutableMapOf<String, Any>()
                    val errors = mutableListOf<Map<String, String>>()
                    var totalChecks = 0
                    var passedChecks = 0
                    var failedChecks = 0
                    var skippedChecks = 0

                    val pgSchema = connectionService.getPostgresSchema(project)
                    val tablesToValidate = project.getTableList()

                    logLine("=== Migration Validation Started ===")
                    logLine("Project: ${project.name} (ID: $projectId)")
                    logLine("Scope: ${scope.joinToString(", ")}")
                    logLine("Tables to validate: ${if (tablesToValidate.isEmpty()) "ALL" else tablesToValidate.joinToString(", ")}")
                    logLine("")

                    if ("ROW_COUNTS" in scope) {
                        logLine(">>> PHASE 1: Row Count Validation (JDBC)...")
                        try {
                            val oracleUrl = connectionService.buildOracleJdbcUrl(project)
                            val pgUrl = connectionService.buildPostgresJdbcUrl(project)

                            DriverManager.getConnection(oracleUrl, project.oracleUser, project.oraclePassword).use { oraConn ->
                                DriverManager.getConnection(pgUrl, project.postgresUser, project.postgresPassword).use { pgConn ->
                                    val rowCountResults = compareRowCounts(
                                        oraConn, pgConn, project, pgSchema, tablesToValidate, errors, ::logLine
                                    )
                                    val dataTableResults = mutableListOf<Map<String, Any>>()

                                    for ((table, result) in rowCountResults) {
                                        totalChecks++
                                        dataTableResults.add(mutableMapOf("table" to table, "rowCount" to result))
                                        when (result["status"]) {
                                            "PASS" -> passedChecks++
                                            "FAIL" -> failedChecks++
                                            else -> skippedChecks++
                                        }
                                    }
                                    dataValidation["tables"] = dataTableResults
                                }
                            }
                        } catch (e: Exception) {
                            logLine("ERROR in Phase 1: ${e.message}")
                            errors.add(mapOf("table" to "*", "phase" to "row_count", "message" to (e.message ?: "Unknown error")))
                        }
                        logLine("")
                    }

                    val hasSchemaScope = scope.any { it in listOf("TABLES", "PKS", "FKS", "INDEXES", "SEQUENCES", "CONSTRAINTS", "DEFAULTS") }
                    if (hasSchemaScope) {
                        logLine(">>> PHASE 2: Schema Validation (JDBC comparison)...")
                        try {
                            val oracleUrl = connectionService.buildOracleJdbcUrl(project)
                            val pgUrl = connectionService.buildPostgresJdbcUrl(project)

                            DriverManager.getConnection(oracleUrl, project.oracleUser, project.oraclePassword).use { oraConn ->
                                DriverManager.getConnection(pgUrl, project.postgresUser, project.postgresPassword).use { pgConn ->
                                    val schemaResults = compareSchema(
                                        oraConn, pgConn, project, pgSchema, scope, tablesToValidate, errors, ::logLine
                                    )

                                    for ((_, results) in schemaResults.checks) {
                                        totalChecks += results.total
                                        passedChecks += results.passed
                                        failedChecks += results.failed
                                        skippedChecks += results.skipped
                                    }

                                    schemaValidation["tables"] = schemaResults.tables
                                    if (schemaResults.sequences.isNotEmpty()) {
                                        schemaValidation["sequences"] = schemaResults.sequences
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logLine("ERROR in Phase 2: ${e.message}")
                            errors.add(mapOf("table" to "*", "phase" to "schema_comparison", "message" to (e.message ?: "Unknown error")))
                        }
                        logLine("")
                    }

                    if ("CHECKSUMS" in scope) {
                        logLine(">>> PHASE 3: Data Checksum Validation (JDBC comparison)...")
                        try {
                            val oracleUrl = connectionService.buildOracleJdbcUrl(project)
                            val pgUrl = connectionService.buildPostgresJdbcUrl(project)

                            DriverManager.getConnection(oracleUrl, project.oracleUser, project.oraclePassword).use { oraConn ->
                                DriverManager.getConnection(pgUrl, project.postgresUser, project.postgresPassword).use { pgConn ->
                                    val checksumResults = compareChecksums(
                                        oraConn, pgConn, project, pgSchema, tablesToValidate, errors, ::logLine
                                    )

                                    totalChecks += checksumResults.total
                                    passedChecks += checksumResults.passed
                                    failedChecks += checksumResults.failed
                                    skippedChecks += checksumResults.skipped

                                    @Suppress("UNCHECKED_CAST")
                                    val existingDataTables = dataValidation.getOrDefault("tables", mutableListOf<Map<String, Any>>()) as MutableList<Map<String, Any>>
                                    for ((table, checksums) in checksumResults.tableChecksums) {
                                        val existing = existingDataTables.find { (it["table"] as? String)?.equals(table, ignoreCase = true) == true }
                                        if (existing != null && existing is MutableMap) {
                                            existing["checksums"] = checksums
                                        } else {
                                            existingDataTables.add(mutableMapOf("table" to table, "checksums" to checksums))
                                        }
                                    }
                                    dataValidation["tables"] = existingDataTables
                                }
                            }
                        } catch (e: Exception) {
                            logLine("ERROR in Phase 3: ${e.message}")
                            errors.add(mapOf("table" to "*", "phase" to "data_checksum", "message" to (e.message ?: "Unknown error")))
                        }
                        logLine("")
                    }

                    val summary = mapOf(
                        "totalChecks" to totalChecks,
                        "passed" to passedChecks,
                        "failed" to failedChecks,
                        "skipped" to skippedChecks
                    )
                    report["summary"] = summary
                    if (schemaValidation.isNotEmpty()) report["schemaValidation"] = schemaValidation
                    if (dataValidation.isNotEmpty()) report["dataValidation"] = dataValidation
                    if (errors.isNotEmpty()) report["errors"] = errors

                    run.totalChecks = totalChecks
                    run.passedChecks = passedChecks
                    run.failedChecks = failedChecks
                    run.skippedChecks = skippedChecks

                    run.status = when {
                        totalChecks == 0 -> "SUCCESS"
                        failedChecks == 0 -> "SUCCESS"
                        passedChecks == 0 -> "FAILED"
                        else -> "PARTIAL"
                    }

                    report["status"] = run.status
                    report["endTime"] = LocalDateTime.now().toString()

                    val reportFile = baseWorkDir.resolve(run.reportFileName)
                    Files.writeString(reportFile, objectMapper.writeValueAsString(report))

                    logLine("=== Validation Complete ===")
                    logLine("Status: ${run.status}")
                    logLine("Total: $totalChecks | Passed: $passedChecks | Failed: $failedChecks | Skipped: $skippedChecks")

                } catch (e: Exception) {
                    fileWriter.println("Internal Error: ${e.message}")
                    fileWriter.flush()
                    run.status = "FAILED"
                } finally {
                    run.endTime = LocalDateTime.now()
                    validationRunRepository.save(run)
                    try { emitter.complete() } catch (_: Exception) {}
                }
            }
        }
        return emitter
    }
}
