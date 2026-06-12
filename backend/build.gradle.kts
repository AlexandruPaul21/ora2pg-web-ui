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
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")

	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	runtimeOnly("org.xerial:sqlite-jdbc")
	implementation("org.hibernate.orm:hibernate-community-dialects")

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

val frontendDir = file("${project.rootDir}/../frontend")

val installFrontend = tasks.register<Exec>("installFrontend") {
	workingDir = frontendDir
	inputs.file(file("$frontendDir/package.json"))
	inputs.file(file("$frontendDir/package-lock.json"))
	outputs.dir(file("$frontendDir/node_modules"))

	if (Os.isFamily(Os.FAMILY_WINDOWS)) {
		commandLine("npm.cmd", "install")
	} else {
		commandLine("npm", "install")
	}
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
	dependsOn(installFrontend)
	workingDir = frontendDir
	inputs.dir(file("$frontendDir/src"))
	outputs.dir(file("$frontendDir/dist"))

	if (Os.isFamily(Os.FAMILY_WINDOWS)) {
		commandLine("npm.cmd", "run", "build")
	} else {
		commandLine("npm", "run", "build")
	}
}

tasks.named<ProcessResources>("processResources") {
	dependsOn(buildFrontend)

	val frontendBuildDir = file("$frontendDir/dist/frontend/browser")

	from(frontendBuildDir) {
		into("static")
	}
}
