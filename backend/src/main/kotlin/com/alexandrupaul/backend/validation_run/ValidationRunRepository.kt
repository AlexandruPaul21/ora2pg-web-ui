package com.alexandrupaul.backend.validation_run

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ValidationRunRepository : JpaRepository<ValidationRun, Long> {
    fun findByProjectIdOrderByStartTimeDesc(projectId: Long): List<ValidationRun>
}
