import org.apache.tools.ant.taskdefs.condition.Os

plugins {
	id("org.springframework.boot") version "3.4.2"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	kotlin("plugin.jpa") version "1.9.25"
}

group = "com.alexandrupaul"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Core
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")

	// Kotlin Extensions
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// SQLite Driver & Dialect
	// We use hibernate-community-dialects so Spring knows how to talk SQL to SQLite
	runtimeOnly("org.xerial:sqlite-jdbc")
	implementation("org.hibernate.orm:hibernate-community-dialects")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.3.0.23.09")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// =========================================================================
// FRONTEND INTEGRATION TASKS
// =========================================================================

val frontendDir = file("${project.rootDir}/../frontend")

// 1. Install Node Dependencies (npm install)
val installFrontend = tasks.register<Exec>("installFrontend") {
	workingDir = frontendDir
	inputs.file(file("$frontendDir/package.json"))
	inputs.file(file("$frontendDir/package-lock.json"))
	outputs.dir(file("$frontendDir/node_modules"))

	// Windows compatibility check
	if (Os.isFamily(Os.FAMILY_WINDOWS)) {
		commandLine("npm.cmd", "install")
	} else {
		commandLine("npm", "install")
	}
}

// 2. Build Angular App (npm run build)
val buildFrontend = tasks.register<Exec>("buildFrontend") {
	dependsOn(installFrontend) // Ensure deps are installed first
	workingDir = frontendDir
	inputs.dir(file("$frontendDir/src"))
	outputs.dir(file("$frontendDir/dist"))

	if (Os.isFamily(Os.FAMILY_WINDOWS)) {
		commandLine("npm.cmd", "run", "build")
	} else {
		commandLine("npm", "run", "build")
	}
}

// 3. Copy Frontend Build to Spring Boot Resources
// This hooks into the processResources task so the files are bundled into the JAR
tasks.named<ProcessResources>("processResources") {
	dependsOn(buildFrontend)

	// Define where Angular puts the built files (Angular 17+ defaults to dist/project-name/browser)
	// IMPORTANT: Check your angular.json if "outputPath" is different
	val frontendBuildDir = file("$frontendDir/dist/frontend/browser")

	from(frontendBuildDir) {
		into("static") // Copies into src/main/resources/static (inside the jar)
	}
}
