package com.alexandrupaul.backend.migration

import com.alexandrupaul.backend.migration_run.MigrationRun
import com.alexandrupaul.backend.migration_run.MigrationRunService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/migration")
@CrossOrigin(origins = ["http://localhost:4200"])
class MigrationController(
    private val migrationService: MigrationService,
    private val migrationRunService: MigrationRunService,
) {
    private val baseWorkDir = java.nio.file.Paths.get("/data/projects")

    @GetMapping("/history/{projectId}")
    fun getHistory(@PathVariable projectId: Long): ResponseEntity<List<MigrationRun>> {
        return ResponseEntity.ok(migrationRunService.findByProjectIdOrderByStartTimeDesc(projectId))
    }

    @GetMapping("/history/logs/{runId}", produces = ["text/plain"])
    fun getRunLogs(@PathVariable runId: Long): ResponseEntity<String> {
        val run = migrationRunService.findByIdOrThrow(runId)
        val logFile = baseWorkDir.resolve(run.logFileName)
        return if (java.nio.file.Files.exists(logFile)) {
            ResponseEntity.ok(java.nio.file.Files.readString(logFile))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/run/{projectId}")
    fun runMigration(@PathVariable projectId: Long): SseEmitter {
        return migrationService.runMigration(projectId)
    }

    @GetMapping("/report/{projectId}", produces = ["text/html"])
    fun getAssessmentReport(@PathVariable projectId: Long): ResponseEntity<String> {
        return try {
            val htmlReport = migrationService.generateAssessmentReport(projectId)
            ResponseEntity.ok(htmlReport)
        } catch (e: Exception) {
            ResponseEntity.internalServerError().body("<h3>Error generating report: ${e.message}</h3>")
        }
    }
}
