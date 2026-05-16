package com.alexandrupaul.backend.validation

import com.alexandrupaul.backend.validation_run.ValidationRun
import com.alexandrupaul.backend.validation_run.ValidationRunRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.nio.file.Paths

@RestController
@RequestMapping("/api/validation")
@CrossOrigin(origins = ["http://localhost:4200"])
class ValidationController(
    private val validationService: ValidationService,
    private val validationRunRepository: ValidationRunRepository,
) {

    private val baseWorkDir = Paths.get("/data/projects")

    @GetMapping("/run/{projectId}")
    fun runValidation(
        @PathVariable projectId: Long,
        @RequestParam scope: String
    ): SseEmitter {
        val scopeList = scope.split(",").filter { it.isNotBlank() }
        return validationService.runValidation(projectId, scopeList)
    }

    @GetMapping("/history/{projectId}")
    fun getHistory(@PathVariable projectId: Long): ResponseEntity<List<ValidationRun>> {
        return ResponseEntity.ok(validationRunRepository.findByProjectIdOrderByStartTimeDesc(projectId))
    }

    @GetMapping("/history/logs/{runId}", produces = ["text/plain"])
    fun getRunLogs(@PathVariable runId: Long): ResponseEntity<String> {
        val run = validationRunRepository.findById(runId).orElseThrow()
        val logFile = baseWorkDir.resolve(run.logFileName)
        return if (Files.exists(logFile)) {
            ResponseEntity.ok(Files.readString(logFile))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/report/{runId}", produces = ["application/json"])
    fun getReport(@PathVariable runId: Long): ResponseEntity<String> {
        val run = validationRunRepository.findById(runId).orElseThrow()
        val reportFile = baseWorkDir.resolve(run.reportFileName)
        return if (Files.exists(reportFile)) {
            ResponseEntity.ok(Files.readString(reportFile))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
