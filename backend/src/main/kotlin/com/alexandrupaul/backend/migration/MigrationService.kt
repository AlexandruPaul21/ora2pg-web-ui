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

@Service
class MigrationService(
    private val projectRepository: ProjectRepository,
    private val configGenerator: ConfigGenerator,
    private val migrationRunRepository: MigrationRunRepository,
) {

    private val executor = Executors.newCachedThreadPool()
    private val baseWorkDir = Paths.get("/data/projects") // Matches our Docker volume

    fun runMigration(projectId: Long): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)
        val project = projectRepository.findById(projectId).orElseThrow()

        // 1. Create the Run Record
        var run = MigrationRun(projectId = projectId)
        run = migrationRunRepository.save(run)

        // 2. Define the log file path
        if (!Files.exists(baseWorkDir)) Files.createDirectories(baseWorkDir)
        val logFile = baseWorkDir.resolve("run_${run.id}.log")
        run.logFileName = logFile.fileName.toString()
        migrationRunRepository.save(run)

        executor.submit {
            // 3. Open a file writer. Everything inside this block gets saved!
            logFile.toFile().printWriter().use { fileWriter ->

                // Helper function to send to UI AND save to file simultaneously
                fun logLine(line: String) {
                    fileWriter.println(line)
                    fileWriter.flush() // Ensure it writes to disk immediately
                    emitter.send(SseEmitter.event().name("log").data(line))
                }

                try {
                    val configFile = configGenerator.createConfig(project, baseWorkDir)

                    // >>> STEP 1: Schema Extraction
                    logLine("\n>>> STEP 1: Extracting Schema from Oracle...")
                    val schemaFileName = "schema_${project.id}.sql"
                    val p1 = ProcessBuilder("ora2pg", "-c", configFile.toAbsolutePath().toString(), "-t", "TABLE", "-o", schemaFileName, "-b", baseWorkDir.toString())
                        .redirectErrorStream(true).start()

                    BufferedReader(InputStreamReader(p1.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) logLine(line!!)
                    }
                    p1.waitFor()

                    // >>> STEP 2: Apply to Postgres
                    logLine("\n>>> STEP 2: Creating Tables in PostgreSQL...")
                    val schemaFile = baseWorkDir.resolve(schemaFileName)
                    val p2Builder = ProcessBuilder("psql", "-h", project.postgresHost, "-p", project.postgresPort.toString(), "-U", project.postgresUser, "-d", project.postgresDb, "-f", schemaFile.toAbsolutePath().toString())
                    p2Builder.environment()["PGPASSWORD"] = project.postgresPassword
                    p2Builder.redirectErrorStream(true)
                    val p2 = p2Builder.start()

                    BufferedReader(InputStreamReader(p2.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) logLine(line!!)
                    }
                    p2.waitFor()

                    // >>> STEP 3: Migrate Data
                    logLine("\n>>> STEP 3: Migrating Data...")
                    val p3 = ProcessBuilder("ora2pg", "-c", configFile.toAbsolutePath().toString(), "-t", "COPY")
                        .redirectErrorStream(true).start()

                    BufferedReader(InputStreamReader(p3.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) logLine(line!!)
                    }
                    val exitCode = p3.waitFor()

                    if (exitCode == 0) {
                        logLine("Migration Finished Successfully!")
                        run.status = "SUCCESS"
                    } else {
                        logLine("Migration Failed with exit code: $exitCode")
                        run.status = "FAILED"
                    }

                } catch (e: Exception) {
                    logLine("Internal Error: ${e.message}")
                    run.status = "FAILED"
                } finally {
                    // 4. Mark the run as finished in the DB
                    run.endTime = java.time.LocalDateTime.now()
                    migrationRunRepository.save(run)
                    emitter.complete()
                }
            }
        }
        return emitter
    }

    fun generateAssessmentReport(projectId: Long): String {
        val project = projectRepository.findById(projectId).orElseThrow()
        if (!Files.exists(baseWorkDir)) Files.createDirectories(baseWorkDir)
        val configFile = configGenerator.createConfig(project, baseWorkDir)

        val reportFile = baseWorkDir.resolve("report_${project.id}.html")
        val errorFile = baseWorkDir.resolve("report_error_${project.id}.log")

        // Command to generate the HTML report
        val processBuilder = ProcessBuilder(
            "ora2pg",
            "-c", configFile.toAbsolutePath().toString(),
            "-t", "SHOW_REPORT",
            "--dump_as_html"
        )

        // We redirect standard output directly into our HTML file
        processBuilder.redirectOutput(reportFile.toFile())
        // We redirect errors to a separate file so they don't corrupt the HTML
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
