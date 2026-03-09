package com.alexandrupaul.backend.migration_run

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MigrationRunRepository : JpaRepository<MigrationRun, Long> {
    fun findByProjectIdOrderByStartTimeDesc(projectId: Long): List<MigrationRun>
}
