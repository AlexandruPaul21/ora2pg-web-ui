package com.alexandrupaul.backend.migration

import com.alexandrupaul.backend.migration_run.MigrationRun
import com.alexandrupaul.backend.migration_run.MigrationRunRepository
import com.alexandrupaul.backend.project.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.stream.Collectors

@Service
class MigrationService(
    private val projectRepository: ProjectRepository,
    private val configGenerator: ConfigGenerator,
    private val migrationRunRepository: MigrationRunRepository,
) {

    private val executor = Executors.newCachedThreadPool()
    private val baseWorkDir = Paths.get("/data/projects") // Matches docker volume

    fun runMigration(projectId: Long): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)
        val project = projectRepository.findById(projectId).orElseThrow()

        var run = MigrationRun(projectId = projectId)
        run = migrationRunRepository.save(run)

        if (!Files.exists(baseWorkDir)) Files.createDirectories(baseWorkDir)
        val logFile = baseWorkDir.resolve("run_${run.id}.log")
        run.logFileName = logFile.fileName.toString()
        migrationRunRepository.save(run)

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
                    val configFile = configGenerator.createConfig(project, baseWorkDir)

                    fun runOra2pg(type: String, extraArgs: List<String> = emptyList()): Int {
                        val cmd = mutableListOf("ora2pg", "-c", configFile.toAbsolutePath().toString(), "-t", type)
                        cmd.addAll(extraArgs)
                        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                        BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) logLine(line!!)
                        }
                        return p.waitFor()
                    }

                    fun runPsql(sqlFile: java.nio.file.Path): Boolean {
                        val cmd = listOf("psql", "-h", project.postgresHost, "-p", project.postgresPort.toString(), "-U", project.postgresUser, "-d", project.postgresDb, "-f", sqlFile.toAbsolutePath().toString())
                        val pb = ProcessBuilder(cmd)
                        pb.environment()["PGPASSWORD"] = project.postgresPassword
                        pb.redirectErrorStream(true)
                        val p = pb.start()
                        var sawError = false
                        BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                logLine(line!!)
                                if (line.contains("ERROR:")) sawError = true
                            }
                        }
                        p.waitFor()
                        return sawError
                    }

                    var hasErrors = false

                    // >>> STEP 1: Schema Extraction (tables + sequences)
                    logLine("\n>>> STEP 1: Extracting Schema from Oracle...")
                    val schemaFileName = "schema_${project.id}.sql"
                    val sequenceFileName = "sequence_${project.id}.sql"
                    runOra2pg("TABLE", listOf("-o", schemaFileName, "-b", baseWorkDir.toString()))
                    runOra2pg("SEQUENCE", listOf("-o", sequenceFileName, "-b", baseWorkDir.toString()))

                    // >>> STEP 2: Apply Schema to Postgres (psql continues past errors so independent tables survive)
                    logLine("\n>>> STEP 2: Creating Tables in PostgreSQL...")
                    val schemaFile = baseWorkDir.resolve(schemaFileName)
                    if (runPsql(schemaFile)) hasErrors = true

                    val sequenceFile = baseWorkDir.resolve(sequenceFileName)
                    if (Files.exists(sequenceFile) && Files.size(sequenceFile) > 0) {
                        logLine("Applying sequences...")
                        if (runPsql(sequenceFile)) hasErrors = true
                    }

                    // >>> STEP 3: Migrate Data
                    logLine("\n>>> STEP 3: Migrating Data...")
                    val dataExitCode = runOra2pg("COPY")

                    // >>> STEP 4: Apply Foreign Key Constraints
                    val fkeyFiles = Files.list(baseWorkDir)
                        .filter { val name = it.fileName.toString(); name.startsWith("CONSTRAINTS_") && name.endsWith(".sql") && name.contains("${project.id}") }
                        .collect(Collectors.toList())

                    if (fkeyFiles.isNotEmpty()) {
                        logLine("\n>>> STEP 4: Applying Foreign Key Constraints...")
                        for (fkeyFile in fkeyFiles) {
                            logLine("Applying ${fkeyFile.fileName}...")
                            if (runPsql(fkeyFile)) hasErrors = true
                        }
                    }

                    if (dataExitCode != 0) {
                        logLine("Migration Failed with exit code: $dataExitCode")
                        run.status = "FAILED"
                    } else if (hasErrors) {
                        logLine("Migration completed with some errors (check logs above).")
                        run.status = "SUCCESS_WITH_WARNING"
                    } else {
                        logLine("Migration Finished Successfully!")
                        run.status = "SUCCESS"
                    }

                } catch (e: Exception) {
                    fileWriter.println("Internal Error: ${e.message}")
                    fileWriter.flush()
                    run.status = "FAILED"
                } finally {
                    run.endTime = java.time.LocalDateTime.now()
                    migrationRunRepository.save(run)
                    try { emitter.complete() } catch (_: Exception) {}
                }
            }
        }
        return emitter
    }

    fun generateAssessmentReport(projectId: Long): String {
        val project = projectRepository.findById(projectId).orElseThrow()
        if (!Files.exists(baseWorkDir)) Files.createDirectories(baseWorkDir)
        val configFile = configGenerator.createConfig(project, baseWorkDir, includeTableFilter = false)

        val reportFile = baseWorkDir.resolve("report_${project.id}.html")
        val errorFile = baseWorkDir.resolve("report_error_${project.id}.log")

        val processBuilder = ProcessBuilder(
            "ora2pg",
            "-c", configFile.toAbsolutePath().toString(),
            "-t", "SHOW_REPORT",
            "--dump_as_html"
        )

        processBuilder.redirectOutput(reportFile.toFile())
        processBuilder.redirectError(errorFile.toFile())

        val process = processBuilder.start()
        val exitCode = process.waitFor()

        if (exitCode == 0 && Files.exists(reportFile)) {
            return Files.readString(reportFile)
        } else {
            val errorLog = if (Files.exists(errorFile)) Files.readString(errorFile) else "Unknown error"
            throw RuntimeException("Report Generation Failed (Exit Code: $exitCode). Logs: $errorLog")
        }
    }
}
