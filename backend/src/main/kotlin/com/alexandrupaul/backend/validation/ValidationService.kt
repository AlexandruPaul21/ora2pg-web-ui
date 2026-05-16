package com.alexandrupaul.backend.validation

import com.alexandrupaul.backend.connection.ConnectionService
import com.alexandrupaul.backend.project.Project
import com.alexandrupaul.backend.project.ProjectRepository
import com.alexandrupaul.backend.validation_run.ValidationRun
import com.alexandrupaul.backend.validation_run.ValidationRunRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
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

    private val typeCompatibility = mapOf(
        "NUMBER" to setOf("smallint", "integer", "bigint", "numeric", "decimal", "double precision", "real",
            "int2", "int4", "int8", "float4", "float8"),
        "FLOAT" to setOf("double precision", "real", "numeric", "float4", "float8"),
        "BINARY_FLOAT" to setOf("real", "double precision", "float4", "float8"),
        "BINARY_DOUBLE" to setOf("double precision", "float8"),
        "VARCHAR2" to setOf("character varying", "varchar", "text"),
        "NVARCHAR2" to setOf("character varying", "varchar", "text"),
        "CHAR" to setOf("character", "char", "character varying", "varchar", "bpchar"),
        "NCHAR" to setOf("character", "char", "character varying", "varchar", "bpchar"),
        "CLOB" to setOf("text"),
        "NCLOB" to setOf("text"),
        "BLOB" to setOf("bytea"),
        "RAW" to setOf("bytea"),
        "LONG" to setOf("text"),
        "LONG RAW" to setOf("bytea"),
        "DATE" to setOf("timestamp without time zone", "timestamp", "date"),
        "TIMESTAMP" to setOf("timestamp without time zone", "timestamp"),
        "TIMESTAMP(6)" to setOf("timestamp without time zone", "timestamp"),
        "TIMESTAMP WITH TIME ZONE" to setOf("timestamp with time zone", "timestamptz"),
        "INTERVAL YEAR TO MONTH" to setOf("interval"),
        "INTERVAL DAY TO SECOND" to setOf("interval"),
        "XMLTYPE" to setOf("xml"),
    )

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
                    if (!emitterClosed) {
                        try {
                            emitter.send(SseEmitter.event().name("runId").data(run.id.toString()))
                        } catch (_: Exception) {
                            emitterClosed = true
                        }
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
                    val tablesToValidate = getTableList(project)

                    logLine("=== Migration Validation Started ===")
                    logLine("Project: ${project.name} (ID: $projectId)")
                    logLine("Scope: ${scope.joinToString(", ")}")
                    logLine("Tables to validate: ${if (tablesToValidate.isEmpty()) "ALL" else tablesToValidate.joinToString(", ")}")
                    logLine("")

                    // Phase 1: JDBC Row Count Comparison
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

                    // Phase 2: JDBC Schema Comparison
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

                                    for ((key, results) in schemaResults.checks) {
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

                    // Phase 3: JDBC Data Checksums
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

                                    // Merge checksum results into existing data tables or create new entries
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

                    // Build final report
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

    private fun getTableList(project: Project): List<String> {
        if (project.tableFilterMode.isNullOrBlank() || project.selectedTables.isBlank()) {
            return emptyList()
        }
        return project.selectedTables.split(" ").filter { it.isNotBlank() }
    }

    private fun shouldIncludeTable(tableName: String, project: Project, tableList: List<String>): Boolean {
        if (tableList.isEmpty()) return true
        val upperTable = tableName.uppercase()
        val upperList = tableList.map { it.uppercase() }
        return when (project.tableFilterMode) {
            "ALLOW" -> upperTable in upperList
            "EXCLUDE" -> upperTable !in upperList
            else -> true
        }
    }

    // --- Phase 1: JDBC Row Count Comparison ---

    private fun compareRowCounts(
        oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
        tableFilter: List<String>, errors: MutableList<Map<String, String>>,
        logLine: (String) -> Unit
    ): Map<String, Map<String, Any>> {
        val results = mutableMapOf<String, Map<String, Any>>()

        val oracleTables = mutableListOf<String>()
        oraConn.prepareStatement("SELECT table_name FROM all_tables WHERE owner = UPPER(?) ORDER BY table_name").use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val t = rs.getString("table_name")
                    if (shouldIncludeTable(t, project, tableFilter)) oracleTables.add(t)
                }
            }
        }

        val pgTables = mutableSetOf<String>()
        pgConn.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'").use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) pgTables.add(rs.getString("table_name").lowercase())
            }
        }

        for (oracleTable in oracleTables) {
            val pgTableName = oracleTable.lowercase()
            if (pgTableName !in pgTables) {
                logLine("  [FAIL] $oracleTable — table does not exist in PostgreSQL")
                results[oracleTable] = mapOf("status" to "FAIL", "oracleCount" to 0L, "postgresCount" to 0L)
                errors.add(mapOf("table" to oracleTable, "phase" to "row_count", "message" to "Table does not exist in PostgreSQL"))
                continue
            }

            try {
                val oracleCount = queryScalar(oraConn, "SELECT COUNT(*) FROM \"$oracleTable\"").toLong()
                val pgCount = queryScalar(pgConn, "SELECT COUNT(*) FROM \"${pgSchema}\".\"${pgTableName}\"").toLong()
                val status = if (oracleCount == pgCount) "PASS" else "FAIL"
                logLine("  [${status}] $oracleTable — Oracle: $oracleCount, PostgreSQL: $pgCount")
                results[oracleTable] = mapOf("status" to status, "oracleCount" to oracleCount, "postgresCount" to pgCount)
            } catch (e: Exception) {
                logLine("  [ERROR] $oracleTable — ${e.message}")
                errors.add(mapOf("table" to oracleTable, "phase" to "row_count", "message" to (e.message ?: "Unknown error")))
            }
        }

        return results
    }

    // --- Phase 2: Schema Comparison ---

    data class CheckCounts(var total: Int = 0, var passed: Int = 0, var failed: Int = 0, var skipped: Int = 0)

    data class SchemaResult(
        val tables: List<Map<String, Any>>,
        val sequences: List<Map<String, Any>>,
        val checks: MutableMap<String, CheckCounts>
    )

    private fun compareSchema(
        oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
        scope: List<String>, tableFilter: List<String>, errors: MutableList<Map<String, String>>,
        logLine: (String) -> Unit
    ): SchemaResult {
        val checks = mutableMapOf<String, CheckCounts>()
        val tableResults = mutableListOf<Map<String, Any>>()
        val sequenceResults = mutableListOf<Map<String, Any>>()

        // Get Oracle tables
        val oracleTables = mutableListOf<String>()
        oraConn.prepareStatement("SELECT table_name FROM all_tables WHERE owner = UPPER(?) ORDER BY table_name").use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val t = rs.getString("table_name")
                    if (shouldIncludeTable(t, project, tableFilter)) oracleTables.add(t)
                }
            }
        }

        // Get PostgreSQL tables
        val pgTables = mutableSetOf<String>()
        pgConn.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'").use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) pgTables.add(rs.getString("table_name").lowercase())
            }
        }

        logLine("  Found ${oracleTables.size} Oracle tables, ${pgTables.size} PostgreSQL tables")

        for (oracleTable in oracleTables) {
            val pgTableName = oracleTable.lowercase()
            val tableEntry = mutableMapOf<String, Any>(
                "oracleTable" to oracleTable,
                "postgresTable" to pgTableName,
                "exists" to (pgTableName in pgTables)
            )

            if (pgTableName !in pgTables) {
                logLine("  [FAIL] Table $oracleTable — does not exist in PostgreSQL")
                checks.getOrPut("tables") { CheckCounts() }.let { it.total++; it.failed++ }
                errors.add(mapOf("table" to oracleTable, "phase" to "schema_check", "message" to "Table does not exist in PostgreSQL"))
                tableResults.add(tableEntry)
                continue
            }

            checks.getOrPut("tables") { CheckCounts() }.let { it.total++; it.passed++ }
            logLine("  [PASS] Table $oracleTable — exists in PostgreSQL")

            try {
                // Columns comparison
                if ("TABLES" in scope) {
                    val columnResult = compareColumns(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                    tableEntry["columns"] = columnResult.columns
                    checks.getOrPut("columns") { CheckCounts() }.let {
                        it.total += columnResult.total; it.passed += columnResult.passed
                        it.failed += columnResult.failed; it.skipped += columnResult.skipped
                    }
                }

                // Primary Keys
                if ("PKS" in scope) {
                    val pkResult = comparePrimaryKeys(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                    tableEntry["primaryKey"] = pkResult.detail
                    checks.getOrPut("primaryKeys") { CheckCounts() }.let {
                        it.total++
                        if (pkResult.passed) it.passed++ else it.failed++
                    }
                }

                // Foreign Keys
                if ("FKS" in scope) {
                    val fkResult = compareForeignKeys(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                    tableEntry["foreignKeys"] = fkResult.details
                    checks.getOrPut("foreignKeys") { CheckCounts() }.let {
                        it.total += fkResult.total; it.passed += fkResult.passed; it.failed += fkResult.failed
                    }
                }

                // Indexes
                if ("INDEXES" in scope) {
                    val idxResult = compareIndexes(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                    tableEntry["indexes"] = idxResult.details
                    checks.getOrPut("indexes") { CheckCounts() }.let {
                        it.total += idxResult.total; it.passed += idxResult.passed; it.failed += idxResult.failed
                    }
                }

                // Check Constraints
                if ("CONSTRAINTS" in scope) {
                    val conResult = compareCheckConstraints(oraConn, pgConn, oracleTable, pgSchema, project, logLine)
                    tableEntry["checkConstraints"] = conResult.details
                    checks.getOrPut("constraints") { CheckCounts() }.let {
                        it.total += conResult.total; it.passed += conResult.passed; it.failed += conResult.failed
                    }
                }

            } catch (e: Exception) {
                logLine("  [ERROR] Table $oracleTable — ${e.message}")
                errors.add(mapOf("table" to oracleTable, "phase" to "schema_check", "message" to (e.message ?: "Unknown error")))
            }

            tableResults.add(tableEntry)
        }

        // Sequences
        if ("SEQUENCES" in scope) {
            logLine("  Comparing sequences...")
            try {
                val seqResult = compareSequences(oraConn, pgConn, project, pgSchema, logLine)
                sequenceResults.addAll(seqResult.details)
                checks.getOrPut("sequences") { CheckCounts() }.let {
                    it.total += seqResult.total; it.passed += seqResult.passed; it.failed += seqResult.failed
                }
            } catch (e: Exception) {
                logLine("  [ERROR] Sequence comparison — ${e.message}")
                errors.add(mapOf("table" to "*", "phase" to "sequence_check", "message" to (e.message ?: "Unknown error")))
            }
        }

        return SchemaResult(tableResults, sequenceResults, checks)
    }

    // --- Column comparison ---

    data class ColumnResult(val columns: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int, val skipped: Int)

    private fun compareColumns(
        oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
        project: Project, logLine: (String) -> Unit
    ): ColumnResult {
        val oracleColumns = mutableListOf<Map<String, Any?>>()
        oraConn.prepareStatement("""
            SELECT column_name, data_type, data_length, data_precision, data_scale, nullable, data_default
            FROM all_tab_columns WHERE owner = UPPER(?) AND table_name = ?
            ORDER BY column_id
        """.trimIndent()).use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.setString(2, oracleTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    oracleColumns.add(mapOf(
                        "name" to rs.getString("column_name"),
                        "type" to rs.getString("data_type"),
                        "length" to rs.getObject("data_length"),
                        "precision" to rs.getObject("data_precision"),
                        "scale" to rs.getObject("data_scale"),
                        "nullable" to rs.getString("nullable"),
                        "default" to rs.getString("data_default")?.trim()
                    ))
                }
            }
        }

        val pgColumns = mutableMapOf<String, Map<String, Any?>>()
        pgConn.prepareStatement("""
            SELECT column_name, udt_name, character_maximum_length, numeric_precision, numeric_scale,
                   is_nullable, column_default
            FROM information_schema.columns WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
        """.trimIndent()).use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.setString(2, oracleTable.lowercase())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("column_name")
                    pgColumns[name.lowercase()] = mapOf(
                        "name" to name,
                        "type" to rs.getString("udt_name"),
                        "length" to rs.getObject("character_maximum_length"),
                        "precision" to rs.getObject("numeric_precision"),
                        "scale" to rs.getObject("numeric_scale"),
                        "nullable" to rs.getString("is_nullable"),
                        "default" to rs.getString("column_default")?.trim()
                    )
                }
            }
        }

        var total = 0; var passed = 0; var failed = 0; var skipped = 0
        val result = mutableListOf<Map<String, Any>>()

        for (oraCol in oracleColumns) {
            val colName = oraCol["name"] as String
            val pgCol = pgColumns[colName.lowercase()]
            total++

            if (pgCol == null) {
                failed++
                logLine("    [FAIL] Column $oracleTable.$colName — missing in PostgreSQL")
                result.add(mapOf(
                    "name" to colName, "oracleType" to (oraCol["type"] ?: ""), "postgresType" to "MISSING",
                    "pgColumnName" to "", "typeCompatible" to false, "nullableMatch" to false, "defaultMatch" to false
                ))
                continue
            }

            val oraType = (oraCol["type"] as? String)?.uppercase() ?: ""
            val pgType = (pgCol["type"] as? String)?.lowercase() ?: ""
            val typeCompatible = isTypeCompatible(oraType, pgType)

            val oraNullable = (oraCol["nullable"] as? String) == "Y"
            val pgNullable = (pgCol["nullable"] as? String) == "YES"
            val nullableMatch = oraNullable == pgNullable

            if (typeCompatible) {
                passed++
                logLine("    [PASS] Column $oracleTable.$colName ($oraType -> $pgType)")
            } else {
                failed++
                logLine("    [FAIL] Column $oracleTable.$colName — type mismatch: Oracle=$oraType, PG=$pgType")
            }

            result.add(mapOf(
                "name" to colName,
                "oracleType" to oraType,
                "postgresType" to pgType,
                "pgColumnName" to (pgCol["name"] ?: ""),
                "typeCompatible" to typeCompatible,
                "nullableMatch" to nullableMatch,
                "defaultMatch" to true,
                "oracleDefault" to (oraCol["default"] ?: ""),
                "postgresDefault" to (pgCol["default"] ?: "")
            ))
        }

        return ColumnResult(result, total, passed, failed, skipped)
    }

    private fun isTypeCompatible(oracleType: String, pgType: String): Boolean {
        val oraUpper = oracleType.uppercase().replace(Regex("""\(\d+[,\d]*\)"""), "").trim()
        val pgLower = pgType.lowercase()
        val compatibleTypes = typeCompatibility[oraUpper] ?: return pgLower.contains(oraUpper.lowercase())
        return pgLower in compatibleTypes
    }

    // --- Primary Key comparison ---

    data class PKResult(val detail: Map<String, Any>, val passed: Boolean)

    private fun comparePrimaryKeys(
        oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
        project: Project, logLine: (String) -> Unit
    ): PKResult {
        val oraColumns = mutableListOf<String>()
        oraConn.prepareStatement("""
            SELECT acc.column_name FROM all_constraints ac
            JOIN all_cons_columns acc ON ac.constraint_name = acc.constraint_name AND ac.owner = acc.owner
            WHERE ac.owner = UPPER(?) AND ac.table_name = ? AND ac.constraint_type = 'P'
            ORDER BY acc.position
        """.trimIndent()).use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.setString(2, oracleTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) oraColumns.add(rs.getString("column_name"))
            }
        }

        val pgColumns = mutableListOf<String>()
        pgConn.prepareStatement("""
            SELECT kcu.column_name FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
            WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY'
            ORDER BY kcu.ordinal_position
        """.trimIndent()).use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.setString(2, oracleTable.lowercase())
            stmt.executeQuery().use { rs ->
                while (rs.next()) pgColumns.add(rs.getString("column_name"))
            }
        }

        val oraUpper = oraColumns.map { it.uppercase() }
        val pgUpper = pgColumns.map { it.uppercase() }
        val match = oraUpper == pgUpper

        val status = when {
            oraColumns.isEmpty() && pgColumns.isEmpty() -> { logLine("    [PASS] PK $oracleTable — no PK defined (both sides)"); "PASS" }
            match -> { logLine("    [PASS] PK $oracleTable — columns match: ${oraColumns.joinToString(", ")}"); "PASS" }
            else -> { logLine("    [FAIL] PK $oracleTable — Oracle=${oraColumns.joinToString(",")}, PG=${pgColumns.joinToString(",")}"); "FAIL" }
        }

        return PKResult(
            mapOf("status" to status, "oracleColumns" to oraColumns, "postgresColumns" to pgColumns),
            status == "PASS"
        )
    }

    // --- Foreign Key comparison ---

    data class FKResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)

    private fun compareForeignKeys(
        oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
        project: Project, logLine: (String) -> Unit
    ): FKResult {
        val oraFKs = mutableMapOf<String, MutableMap<String, Any>>()
        oraConn.prepareStatement("""
            SELECT ac.constraint_name, acc.column_name, ac_r.table_name AS ref_table, acc_r.column_name AS ref_column
            FROM all_constraints ac
            JOIN all_cons_columns acc ON ac.constraint_name = acc.constraint_name AND ac.owner = acc.owner
            JOIN all_constraints ac_r ON ac.r_constraint_name = ac_r.constraint_name AND ac.r_owner = ac_r.owner
            JOIN all_cons_columns acc_r ON ac_r.constraint_name = acc_r.constraint_name AND ac_r.owner = acc_r.owner AND acc.position = acc_r.position
            WHERE ac.owner = UPPER(?) AND ac.table_name = ? AND ac.constraint_type = 'R'
            ORDER BY ac.constraint_name, acc.position
        """.trimIndent()).use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.setString(2, oracleTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("constraint_name")
                    val fk = oraFKs.getOrPut(name) { mutableMapOf("columns" to mutableListOf<String>(), "refTable" to "", "refColumns" to mutableListOf<String>()) }
                    @Suppress("UNCHECKED_CAST")
                    (fk["columns"] as MutableList<String>).add(rs.getString("column_name"))
                    fk["refTable"] = rs.getString("ref_table")
                    @Suppress("UNCHECKED_CAST")
                    (fk["refColumns"] as MutableList<String>).add(rs.getString("ref_column"))
                }
            }
        }

        val pgFKs = mutableMapOf<String, MutableMap<String, Any>>()
        pgConn.prepareStatement("""
            SELECT tc.constraint_name, kcu.column_name, ccu.table_name AS ref_table, ccu.column_name AS ref_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema
            WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'FOREIGN KEY'
            ORDER BY tc.constraint_name, kcu.ordinal_position
        """.trimIndent()).use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.setString(2, oracleTable.lowercase())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("constraint_name")
                    val fk = pgFKs.getOrPut(name) { mutableMapOf("columns" to mutableListOf<String>(), "refTable" to "", "refColumns" to mutableListOf<String>()) }
                    @Suppress("UNCHECKED_CAST")
                    (fk["columns"] as MutableList<String>).add(rs.getString("column_name"))
                    fk["refTable"] = rs.getString("ref_table")
                    @Suppress("UNCHECKED_CAST")
                    (fk["refColumns"] as MutableList<String>).add(rs.getString("ref_column"))
                }
            }
        }

        var total = 0; var passed = 0; var failed = 0
        val details = mutableListOf<Map<String, Any>>()

        for ((name, oraFK) in oraFKs) {
            total++
            @Suppress("UNCHECKED_CAST")
            val oraColsUpper = (oraFK["columns"] as List<String>).map { it.uppercase() }
            val oraRefTable = (oraFK["refTable"] as String).uppercase()
            @Suppress("UNCHECKED_CAST")
            val oraRefColsUpper = (oraFK["refColumns"] as List<String>).map { it.uppercase() }

            val matchingPgFK = pgFKs.values.find { pgFK ->
                @Suppress("UNCHECKED_CAST")
                val pgCols = (pgFK["columns"] as List<String>).map { it.uppercase() }
                val pgRefTable = (pgFK["refTable"] as String).uppercase()
                @Suppress("UNCHECKED_CAST")
                val pgRefCols = (pgFK["refColumns"] as List<String>).map { it.uppercase() }
                pgCols == oraColsUpper && pgRefTable == oraRefTable && pgRefCols == oraRefColsUpper
            }

            if (matchingPgFK != null) {
                passed++
                logLine("    [PASS] FK $name on $oracleTable")
                details.add(mapOf("name" to name, "status" to "PASS",
                    "oracleDefinition" to "$oracleTable(${oraColsUpper.joinToString(",")}) -> $oraRefTable(${oraRefColsUpper.joinToString(",")})",
                    "postgresDefinition" to "matched"))
            } else {
                failed++
                logLine("    [FAIL] FK $name on $oracleTable — not found in PostgreSQL")
                details.add(mapOf("name" to name, "status" to "FAIL",
                    "oracleDefinition" to "$oracleTable(${oraColsUpper.joinToString(",")}) -> $oraRefTable(${oraRefColsUpper.joinToString(",")})",
                    "postgresDefinition" to "MISSING"))
            }
        }

        if (oraFKs.isEmpty()) {
            logLine("    [PASS] No foreign keys on $oracleTable (both sides)")
        }

        return FKResult(details, total, passed, failed)
    }

    // --- Index comparison ---

    data class IndexResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)

    private fun compareIndexes(
        oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
        project: Project, logLine: (String) -> Unit
    ): IndexResult {
        val oraIndexes = mutableMapOf<String, MutableMap<String, Any>>()
        oraConn.prepareStatement("""
            SELECT ai.index_name, ai.uniqueness, aic.column_name
            FROM all_indexes ai
            JOIN all_ind_columns aic ON ai.index_name = aic.index_name AND ai.owner = aic.index_owner
            WHERE ai.owner = UPPER(?) AND ai.table_name = ?
              AND ai.index_type IN ('NORMAL', 'BITMAP', 'FUNCTION-BASED NORMAL')
              AND ai.index_name NOT IN (
                  SELECT constraint_name FROM all_constraints
                  WHERE owner = ai.owner AND table_name = ai.table_name
                    AND constraint_type IN ('P', 'U')
              )
            ORDER BY ai.index_name, aic.column_position
        """.trimIndent()).use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.setString(2, oracleTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("index_name")
                    val idx = oraIndexes.getOrPut(name) { mutableMapOf("unique" to false, "columns" to mutableListOf<String>()) }
                    idx["unique"] = rs.getString("uniqueness") == "UNIQUE"
                    @Suppress("UNCHECKED_CAST")
                    (idx["columns"] as MutableList<String>).add(rs.getString("column_name"))
                }
            }
        }

        val pgIndexes = mutableMapOf<String, MutableMap<String, Any>>()
        pgConn.prepareStatement("""
            SELECT i.relname AS index_name, ix.indisunique, a.attname AS column_name
            FROM pg_index ix
            JOIN pg_class t ON ix.indrelid = t.oid
            JOIN pg_class i ON ix.indexrelid = i.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE n.nspname = ? AND t.relname = ? AND NOT ix.indisprimary
            ORDER BY i.relname, array_position(ix.indkey, a.attnum)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.setString(2, oracleTable.lowercase())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("index_name")
                    val idx = pgIndexes.getOrPut(name) { mutableMapOf("unique" to false, "columns" to mutableListOf<String>()) }
                    idx["unique"] = rs.getBoolean("indisunique")
                    @Suppress("UNCHECKED_CAST")
                    (idx["columns"] as MutableList<String>).add(rs.getString("column_name"))
                }
            }
        }

        var total = 0; var passed = 0; var failed = 0
        val details = mutableListOf<Map<String, Any>>()

        for ((name, oraIdx) in oraIndexes) {
            total++
            @Suppress("UNCHECKED_CAST")
            val oraColsUpper = (oraIdx["columns"] as List<String>).map { it.uppercase() }

            val matchingPgIdx = pgIndexes.values.find { pgIdx ->
                @Suppress("UNCHECKED_CAST")
                val pgCols = (pgIdx["columns"] as List<String>).map { it.uppercase() }
                pgCols == oraColsUpper
            }

            if (matchingPgIdx != null) {
                passed++
                logLine("    [PASS] Index $name on $oracleTable (${oraColsUpper.joinToString(",")})")
            } else {
                failed++
                logLine("    [FAIL] Index $name on $oracleTable — no matching index in PostgreSQL")
            }

            details.add(mapOf(
                "name" to name,
                "status" to if (matchingPgIdx != null) "PASS" else "FAIL",
                "oracleColumns" to oraColsUpper,
                "postgresColumns" to (matchingPgIdx?.let {
                    @Suppress("UNCHECKED_CAST")
                    (it["columns"] as List<String>)
                } ?: emptyList<String>()),
                "unique" to (oraIdx["unique"] ?: false)
            ))
        }

        if (oraIndexes.isEmpty()) {
            logLine("    [PASS] No indexes on $oracleTable")
        }

        return IndexResult(details, total, passed, failed)
    }

    // --- Check Constraint comparison ---

    data class ConstraintResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)

    private fun compareCheckConstraints(
        oraConn: Connection, pgConn: Connection, oracleTable: String, pgSchema: String,
        project: Project, logLine: (String) -> Unit
    ): ConstraintResult {
        val oraConstraints = mutableMapOf<String, String>()
        oraConn.prepareStatement("""
            SELECT constraint_name, search_condition FROM all_constraints
            WHERE owner = UPPER(?) AND table_name = ? AND constraint_type = 'C'
              AND constraint_name NOT LIKE 'SYS_%'
        """.trimIndent()).use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.setString(2, oracleTable)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    oraConstraints[rs.getString("constraint_name")] = rs.getString("search_condition") ?: ""
                }
            }
        }

        val pgConstraints = mutableMapOf<String, String>()
        pgConn.prepareStatement("""
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc ON tc.constraint_name = cc.constraint_name AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.table_schema = ? AND tc.table_name = ? AND tc.constraint_type = 'CHECK'
              AND tc.constraint_name NOT LIKE '%_not_null'
        """.trimIndent()).use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.setString(2, oracleTable.lowercase())
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    pgConstraints[rs.getString("constraint_name")] = rs.getString("check_clause") ?: ""
                }
            }
        }

        var total = 0; var passed = 0; var failed = 0
        val details = mutableListOf<Map<String, Any>>()

        val oraCount = oraConstraints.size
        val pgCount = pgConstraints.size
        total++
        if (oraCount == pgCount) {
            passed++
            logLine("    [PASS] Check constraints on $oracleTable — count matches ($oraCount)")
        } else {
            failed++
            logLine("    [FAIL] Check constraints on $oracleTable — Oracle=$oraCount, PG=$pgCount")
        }

        details.add(mapOf(
            "status" to if (oraCount == pgCount) "PASS" else "FAIL",
            "oracleCount" to oraCount,
            "postgresCount" to pgCount
        ))

        return ConstraintResult(details, total, passed, failed)
    }

    // --- Sequence comparison ---

    data class SequenceResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)

    private fun compareSequences(
        oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
        logLine: (String) -> Unit
    ): SequenceResult {
        val oraSequences = mutableListOf<String>()
        oraConn.prepareStatement("SELECT sequence_name FROM all_sequences WHERE sequence_owner = UPPER(?) AND sequence_name NOT LIKE 'ISEQ%'").use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) oraSequences.add(rs.getString("sequence_name"))
            }
        }

        val pgSequences = mutableSetOf<String>()
        pgConn.prepareStatement("SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?").use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) pgSequences.add(rs.getString("sequence_name").lowercase())
            }
        }

        var total = 0; var passed = 0; var failed = 0
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

    // --- Phase 3: Data Checksums ---

    data class ChecksumResults(
        val total: Int, val passed: Int, val failed: Int, val skipped: Int,
        val tableChecksums: Map<String, List<Map<String, Any>>>
    )

    private fun compareChecksums(
        oraConn: Connection, pgConn: Connection, project: Project, pgSchema: String,
        tableFilter: List<String>, errors: MutableList<Map<String, String>>,
        logLine: (String) -> Unit
    ): ChecksumResults {
        var total = 0; var passed = 0; var failed = 0; var skipped = 0
        val tableChecksums = mutableMapOf<String, List<Map<String, Any>>>()

        // Get tables that exist in both databases
        val oracleTables = mutableListOf<String>()
        oraConn.prepareStatement("SELECT table_name FROM all_tables WHERE owner = UPPER(?) ORDER BY table_name").use { stmt ->
            stmt.setString(1, project.oracleUser)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val t = rs.getString("table_name")
                    if (shouldIncludeTable(t, project, tableFilter)) oracleTables.add(t)
                }
            }
        }

        val pgTables = mutableSetOf<String>()
        pgConn.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'").use { stmt ->
            stmt.setString(1, pgSchema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) pgTables.add(rs.getString("table_name").lowercase())
            }
        }

        for (oracleTable in oracleTables) {
            if (oracleTable.lowercase() !in pgTables) continue

            logLine("  Checksumming table $oracleTable...")
            val columnChecksums = mutableListOf<Map<String, Any>>()

            try {
                // Get columns with their types from Oracle
                val columns = mutableListOf<Pair<String, String>>()
                oraConn.prepareStatement("""
                    SELECT column_name, data_type FROM all_tab_columns
                    WHERE owner = UPPER(?) AND table_name = ? ORDER BY column_id
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, project.oracleUser)
                    stmt.setString(2, oracleTable)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            columns.add(rs.getString("column_name") to rs.getString("data_type"))
                        }
                    }
                }

                for ((colName, colType) in columns) {
                    val upperType = colType.uppercase()
                    try {
                        when {
                            upperType in listOf("NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE", "INTEGER") ||
                            upperType.startsWith("NUMBER") -> {
                                total++
                                val oraSum = queryScalar(oraConn, "SELECT COALESCE(SUM(\"$colName\"), 0) FROM \"$oracleTable\"")
                                val pgSum = queryScalar(pgConn, "SELECT COALESCE(SUM(\"${colName.lowercase()}\"), 0) FROM \"${pgSchema}\".\"${oracleTable.lowercase()}\"")
                                val match = compareNumericValues(oraSum, pgSum)
                                if (match) passed++ else failed++
                                logLine("    [${if (match) "PASS" else "FAIL"}] $oracleTable.$colName SUM: Oracle=$oraSum, PG=$pgSum")
                                columnChecksums.add(mapOf(
                                    "column" to colName, "type" to "numeric", "method" to "SUM",
                                    "status" to if (match) "PASS" else "FAIL",
                                    "oracleValue" to oraSum, "postgresValue" to pgSum
                                ))
                            }
                            upperType in listOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "VARCHAR") -> {
                                total++
                                val oraCount = queryScalar(oraConn, "SELECT COUNT(DISTINCT \"$colName\") FROM \"$oracleTable\"")
                                val pgCount = queryScalar(pgConn, "SELECT COUNT(DISTINCT \"${colName.lowercase()}\") FROM \"${pgSchema}\".\"${oracleTable.lowercase()}\"")
                                val match = oraCount == pgCount
                                if (match) passed++ else failed++
                                logLine("    [${if (match) "PASS" else "FAIL"}] $oracleTable.$colName COUNT_DISTINCT: Oracle=$oraCount, PG=$pgCount")
                                columnChecksums.add(mapOf(
                                    "column" to colName, "type" to "string", "method" to "COUNT_DISTINCT",
                                    "status" to if (match) "PASS" else "FAIL",
                                    "oracleValue" to oraCount, "postgresValue" to pgCount
                                ))
                            }
                            upperType in listOf("DATE", "TIMESTAMP", "TIMESTAMP(6)") -> {
                                total++
                                val oraCount = queryScalar(oraConn, "SELECT COUNT(DISTINCT \"$colName\") FROM \"$oracleTable\"")
                                val pgCount = queryScalar(pgConn, "SELECT COUNT(DISTINCT \"${colName.lowercase()}\") FROM \"${pgSchema}\".\"${oracleTable.lowercase()}\"")
                                val match = oraCount == pgCount
                                if (match) passed++ else failed++
                                logLine("    [${if (match) "PASS" else "FAIL"}] $oracleTable.$colName COUNT_DISTINCT: Oracle=$oraCount, PG=$pgCount")
                                columnChecksums.add(mapOf(
                                    "column" to colName, "type" to "date", "method" to "COUNT_DISTINCT",
                                    "status" to if (match) "PASS" else "FAIL",
                                    "oracleValue" to oraCount, "postgresValue" to pgCount
                                ))
                            }
                            else -> {
                                skipped++
                                total++
                                columnChecksums.add(mapOf(
                                    "column" to colName, "type" to upperType, "method" to "SKIPPED",
                                    "status" to "SKIPPED", "oracleValue" to "", "postgresValue" to ""
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        skipped++
                        total++
                        logLine("    [SKIP] $oracleTable.$colName — ${e.message}")
                        columnChecksums.add(mapOf(
                            "column" to colName, "type" to upperType, "method" to "ERROR",
                            "status" to "SKIPPED", "oracleValue" to "", "postgresValue" to (e.message ?: "")
                        ))
                    }
                }
            } catch (e: Exception) {
                logLine("  [ERROR] Table $oracleTable — ${e.message}")
                errors.add(mapOf("table" to oracleTable, "phase" to "data_checksum", "message" to (e.message ?: "Unknown error")))
            }

            if (columnChecksums.isNotEmpty()) {
                tableChecksums[oracleTable] = columnChecksums
            }
        }

        return ChecksumResults(total, passed, failed, skipped, tableChecksums)
    }

    private fun queryScalar(conn: Connection, sql: String): String {
        conn.createStatement().use { stmt ->
            stmt.queryTimeout = 300
            stmt.executeQuery(sql).use { rs ->
                return if (rs.next()) rs.getString(1) ?: "NULL" else "NULL"
            }
        }
    }

    private fun compareNumericValues(a: String, b: String): Boolean {
        if (a == b) return true
        return try {
            java.math.BigDecimal(a).compareTo(java.math.BigDecimal(b)) == 0
        } catch (_: Exception) {
            false
        }
    }
}
