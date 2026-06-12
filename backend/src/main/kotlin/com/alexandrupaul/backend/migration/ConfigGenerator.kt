package com.alexandrupaul.backend.migration

import com.alexandrupaul.backend.project.Project
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Component
class ConfigGenerator {

    // [DOC] https://ora2pg.darold.net/docs/configuration
    fun createConfig(project: Project, workDir: Path, includeTableFilter: Boolean = true): Path {
        val suffix = if (includeTableFilter) "" else "_report"
        val configFile = workDir.resolve("ora2pg_${project.id}${suffix}.conf")

        val oracleDsn = when (project.oracleConnectionType) {
            "SERVICE_NAME" -> "dbi:Oracle:host=${project.oracleHost};service_name=${project.oracleSid};port=${project.oraclePort}"
            "CUSTOM" -> project.oracleCustomDsn ?: ""
            else -> "dbi:Oracle:host=${project.oracleHost};sid=${project.oracleSid};port=${project.oraclePort}"
        }

        val pgDsn = "dbi:Pg:dbname=${project.postgresDb};host=${project.postgresHost};port=${project.postgresPort};sslmode=${project.postgresSslMode}"
        val pgSchemaDirective = if (!project.postgresSearchPath.isNullOrBlank()) {
            "PG_SCHEMA     ${project.postgresSearchPath}"
        } else {
            ""
        }

        val tableFilterDirective = if (includeTableFilter && !project.tableFilterMode.isNullOrBlank() && project.selectedTables.isNotBlank()) {
            "${project.tableFilterMode}     ${project.selectedTables}"
        } else ""

        val content = """
            ORACLE_HOME   /opt/oracle/active_client
            ORACLE_DSN    $oracleDsn
            ORACLE_USER   ${project.oracleUser}
            ORACLE_PWD    ${project.oraclePassword}

            PG_DSN        $pgDsn
            PG_USER       ${project.postgresUser}
            PG_PWD        ${project.postgresPassword}
            $pgSchemaDirective

            PG_VERSION    16
            TYPE          COPY
            OUTPUT_DIR    ${workDir.toAbsolutePath()}
            FILE_PER_FKEY 1
            FKEY_DEFERRABLE 1
            DROP_FKEY     1

            # Error handling
            STOP_ON_ERROR 0
            LOG_ON_ERROR  1

            $tableFilterDirective

            # User custom config
            ${project.ora2pgConfig}
        """.trimIndent()

        Files.writeString(configFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return configFile
    }
}
