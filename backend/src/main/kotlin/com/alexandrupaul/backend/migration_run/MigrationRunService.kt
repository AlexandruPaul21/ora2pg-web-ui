package com.alexandrupaul.backend.migration_run

import org.springframework.stereotype.Service

@Service
class MigrationRunService(
    private val runRepo: MigrationRunRepository,
) {
    fun findByProjectIdOrderByStartTimeDesc(projectId: Long): List<MigrationRun> =
        runRepo.findByProjectIdOrderByStartTimeDesc(projectId)

    fun findByIdOrThrow(runId: Long): MigrationRun =
        runRepo.findById(runId).orElseThrow()
}
