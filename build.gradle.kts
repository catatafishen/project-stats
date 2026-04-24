plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    jacoco
}

group = "com.github.codescape"
// Version is set by CI (PLUGIN_VERSION env var) for releases; defaults to a snapshot for local dev.
version = (System.getenv("PLUGIN_VERSION")?.takeIf { it.isNotBlank() }) ?: "0.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.3")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        pluginVerifier()
    }
    testImplementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "261.*"
        }
        // Allow CI to inject release notes (HTML) into the plugin's <change-notes>.
        System.getenv("CHANGELOG_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = file(path)
            if (f.exists()) changeNotes = providers.provider { f.readText() }
        }
    }
    pluginVerification {
        ides {
            // Pin to a known-released IDE to avoid flaky `recommended()` picking
            // versions whose tarballs are not yet published to the download mirror.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.3")
        }
    }
    buildSearchableOptions = false
}

// Plain JUnit test task (no IntelliJ platform classloader) so jacoco can instrument our classes.
// The IntelliJ-platform plugin reconfigures `test` to launch under the JBR/PathClassLoader, which
// prevents the jacoco agent from seeing class definitions for our production code. Running a
// parallel `unitTest` task with a stock JVM keeps pure-Kotlin unit tests measurable.
val unitTest = tasks.register<Test>("unitTest") {
    description = "Runs plain JUnit 5 unit tests outside the IntelliJ platform classloader."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.check {
    dependsOn(unitTest)
}

tasks.jacocoTestReport {
    dependsOn(unitTest)
    executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/unitTest.exec"))
    // Keep coverage focused on the production classes we actually exercise from unit tests.
    // IntelliJ-platform post-instrumentation is irrelevant here because the unitTest task
    // runs on a stock JVM and loads the unmodified kotlin/main classes.
    classDirectories.setFrom(
        fileTree("$buildDir/classes/kotlin/main") {
            include("com/github/projectstats/**")
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }
}
