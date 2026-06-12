package com.alexandrupaul.backend.validation

data class CheckCounts(var total: Int = 0, var passed: Int = 0, var failed: Int = 0, var skipped: Int = 0)

data class ColumnResult(val columns: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int, val skipped: Int)
data class SchemaResult(
    val tables: List<Map<String, Any>>,
    val sequences: List<Map<String, Any>>,
    val checks: MutableMap<String, CheckCounts>
)
data class PKResult(val detail: Map<String, Any>, val passed: Boolean)
data class FKResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)
data class IndexResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)
data class ConstraintResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)
data class SequenceResult(val details: List<Map<String, Any>>, val total: Int, val passed: Int, val failed: Int)
data class ChecksumResults(
    val total: Int, val passed: Int, val failed: Int, val skipped: Int,
    val tableChecksums: Map<String, List<Map<String, Any>>>
)
