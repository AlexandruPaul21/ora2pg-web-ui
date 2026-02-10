package com.alexandrupaul.backend.project

import com.alexandrupaul.backend.connection.ConnectionResult
import com.alexandrupaul.backend.connection.ConnectionService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = ["http://localhost:4200"]) // Allow Angular dev server
class ProjectController(
    private val repository: ProjectRepository,
    private val connectionService: ConnectionService,
) {

    @GetMapping
    fun getAllProjects(): List<Project> = repository.findAll()

    @GetMapping("/{id}")
    fun getProject(@PathVariable id: Long): Project =
        repository.findById(id).orElseThrow { RuntimeException("Project not found") }

    @PostMapping
    fun createProject(@RequestBody project: Project): Project = repository.save(project)

    @PutMapping("/{id}")
    fun updateProject(@PathVariable id: Long, @RequestBody project: Project): Project {
        if (!repository.existsById(id)) throw RuntimeException("Project not found")
        return repository.save(project.copy(id = id))
    }

    @DeleteMapping("/{id}")
    fun deleteProject(@PathVariable id: Long) = repository.deleteById(id)

    @PostMapping("/test-oracle")
    fun testOracleConnection(@RequestBody project: Project): ConnectionResult {
        return connectionService.testOracle(project)
    }

    @PostMapping("/test-postgres")
    fun testPostgresConnection(@RequestBody project: Project): ConnectionResult {
        return connectionService.testPostgres(project)
    }
}
