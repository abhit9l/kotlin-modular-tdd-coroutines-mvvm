// Top-level build file where you can add configuration options common to all sub-projects/modules.

@file:Suppress("UnstableApiUsage")

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.detekt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val javaVersion: JavaVersion by extra { JavaVersion.VERSION_1_8 }
val userHome: String = System.getProperty("user.home")

buildscript {
    repositories {
        google()
        jcenter()
    }
    dependencies {
        classpath(Classpaths.gradle_plugin)
        classpath(Classpaths.kotlin_gradle_plugin)
    }
}

plugins {
    id("io.gitlab.arturbosch.detekt") version Versions.detekt
    id("com.github.ben-manes.versions") version Versions.ben_manes
    id("org.jlleitschuh.gradle.ktlint") version Versions.ktlint
    `build-scan`
    jacoco
}

allprojects {
    repositories {
        google()
        jcenter()
    }
}

val testAll by tasks.registering {
    group = "verification"
    description = "Runs all the tests."

    "${subprojects.forEach {
        dependsOn(
            ":${it.name}:clean",
            ":${it.name}:build",
            ":${it.name}:test",
            ":${it.name}:connectedAndroidTest"
        )
    }}"
}

subprojects {
    apply {
        from("$rootDir/versions.gradle.kts")
        plugin("org.jlleitschuh.gradle.ktlint")
        plugin("io.gitlab.arturbosch.detekt")
        plugin("jacoco")
    }

    ktlint {
        debug.set(true)
    }

    tasks {
        withType<JavaCompile> {
            options.isIncremental = true
            allprojects {
                options.compilerArgs.addAll(
                    arrayOf(
                        "-Xlint:-unchecked",
                        "-Xlint:deprecation",
                        "-Xdiags:verbose"
                    )
                )
            }
        }

        withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = javaVersion.toString()
                // https://youtrack.jetbrains.com/issue/KT-24946
                kotlinOptions.freeCompilerArgs = listOf(
                    "-progressive",
                    "-Xskip-runtime-version-check",
                    "-Xdisable-default-scripting-plugin",
                    "-Xuse-experimental=kotlin.Experimental"
                )
                kotlinOptions.allWarningsAsErrors = shouldTreatCompilerWarningsAsErrors()
            }
        }

        withType<Test> {
            testLogging {
                // set options for log level LIFECYCLE
                events = setOf(
                    TestLogEvent.FAILED,
                    TestLogEvent.STARTED,
                    TestLogEvent.PASSED,
                    TestLogEvent.SKIPPED,
                    TestLogEvent.STANDARD_OUT
                )
                exceptionFormat = TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }

            configure<JacocoTaskExtension> {
                isIncludeNoLocationClasses = true
            }
        }
    }
}

detekt {
    toolVersion = Versions.detekt
    baseline = file("$rootDir/config/detekt/baseline.xml")
    input = files("$projectDir")
    config = files("$rootDir/default-detekt-config.yml")

    reports {
        xml {
            enabled = true
            destination = file("$buildDir/reports/detekt/detekt-report.xml")
        }
        html {
            enabled = true
            destination = file("$buildDir/reports/detekt/detekt-report.html")
        }
    }

    idea {
        path = "$userHome/.idea"
        codeStyleScheme = "$userHome/.idea/idea-code-style.xml"
        inspectionsProfile = "$userHome/.idea/inspect.xml"
        report = "project.projectDir/reports"
        mask = "*.kt"
    }
}

buildScan {
    termsOfServiceUrl = "https://gradle.com/terms-of-service"
    termsOfServiceAgree = "yes"

    /* This is not good to run for every build
    publishAlways()
     */
}

jacoco {
    toolVersion = Versions.jacoco
    reportsDir = file("$buildDir/jacoco/report.xml")
}

tasks {
    register("clean", Delete::class) {
        delete = setOf(rootProject.buildDir)
    }

    withType<Detekt> {
        include("**/*.kt")
        include("**/*.kts")
        exclude(".*/resources/.*")
        exclude(".*/build/.*")

        jvmTarget = javaVersion.toString()
    }

    withType<DependencyUpdatesTask> {
        resolutionStrategy {
            componentSelection {
                all {
                    if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
                        reject("Release candidate")
                    }
                }
            }
        }

        checkForGradleUpdate = true
        outputFormatter = "json"
        outputDir = "$buildDir/reports/dependencyUpdates"
        reportfileName = "dependency-report"
    }

    withType<JacocoReport> {
        reports {
            xml.isEnabled = false
            csv.isEnabled = false
            html.isEnabled = true
            html.destination = file("$buildDir/reports/jacoco/report.html")
        }

        afterEvaluate {
            classDirectories.setFrom(files(classDirectories.files.map {
                fileTree(it).apply {
                    exclude(
                        "**/*_Provide*/**",
                        "**/*_Factory*/**",
                        "**/*_MembersInjector.class",
                        "**/*Dagger*"
                    )
                }
            }))
        }
    }

    withType<JacocoCoverageVerification> {
        violationRules {
            rule {
                limit {
                    minimum = "0.5".toBigDecimal()
                }
            }
        }

        afterEvaluate {
            classDirectories.setFrom(files(classDirectories.files.map {
                fileTree(it).apply {
                    exclude(
                        "**/*_Provide*/**",
                        "**/*_Factory*/**",
                        "**/*_MembersInjector.class",
                        "**/*Dagger*"
                    )
                }
            }))
        }
    }

    withType<Test> {
        configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
        }
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

/**
 * Usage: <code>./gradlew build -PwarningsAsErrors=true</code>.
 */
fun shouldTreatCompilerWarningsAsErrors(): Boolean {
    return project.findProperty("warningsAsErrors") == "true"
}

val detektFormat by tasks.registering(Detekt::class) {
    description = "Reformats whole code base."
    parallel = true
    disableDefaultRuleSets = true
    buildUponDefaultConfig = true
    autoCorrect = true
    setSource(files(projectDir))
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/resources/**")
    exclude("**/build/**")
    config.setFrom(files("$rootDir/config/detekt/format.yml"))
    reports {
        xml.enabled = false
        html.enabled = false
        txt.enabled = false
    }
}

val detektAll by tasks.registering(Detekt::class) {
    description = "Runs over whole code base without the starting overhead for each module."
    parallel = true
    buildUponDefaultConfig = true
    setSource(files(projectDir))
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/resources/**")
    exclude("**/build/**")
    baseline.set(file("$rootDir/config/detekt/baseline.xml"))
    reports {
        xml.enabled = false
        html.enabled = false
        txt.enabled = false
    }
}
