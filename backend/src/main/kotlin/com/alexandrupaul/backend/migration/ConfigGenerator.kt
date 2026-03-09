package com.alexandrupaul.backend.migration

import com.alexandrupaul.backend.project.Project
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Component
class ConfigGenerator {

    fun createConfig(project: Project, workDir: Path): Path {
        val configFile = workDir.resolve("ora2pg_${project.id}.conf")

        // 1. Determine Oracle DSN
        val oracleDsn = when (project.oracleConnectionType) {
            "SERVICE_NAME" -> "dbi:Oracle:host=${project.oracleHost};service_name=${project.oracleSid};port=${project.oraclePort}"
            "CUSTOM" -> project.oracleCustomDsn ?: ""
            else -> "dbi:Oracle:host=${project.oracleHost};sid=${project.oracleSid};port=${project.oraclePort}"
        }

        // 2. Determine Postgres DSN & Schema
        val pgDsn = "dbi:Pg:dbname=${project.postgresDb};host=${project.postgresHost};port=${project.postgresPort};sslmode=${project.postgresSslMode}"
        val pgSchemaDirective = if (!project.postgresSearchPath.isNullOrBlank()) {
            "PG_SCHEMA     ${project.postgresSearchPath}"
        } else {
            ""
        }

        // 3. Build the file content
        val content = """
            ORACLE_HOME   /opt/oracle/active_client
            ORACLE_DSN    $oracleDsn
            ORACLE_USER   ${project.oracleUser}
            ORACLE_PWD    ${project.oraclePassword}
            
            PG_DSN        $pgDsn
            PG_USER       ${project.postgresUser}
            PG_PWD        ${project.postgresPassword}
            $pgSchemaDirective
            
            # Auto-generated settings
            PG_VERSION    16
            TYPE          COPY
            OUTPUT_DIR    ${workDir.toAbsolutePath()}
            FILE_PER_FKEY 1
            
            # User custom config
            ${project.ora2pgConfig}
        """.trimIndent()

        Files.writeString(configFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return configFile
    }
}
