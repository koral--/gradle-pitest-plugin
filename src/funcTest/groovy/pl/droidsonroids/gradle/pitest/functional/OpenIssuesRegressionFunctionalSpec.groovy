package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult
import spock.lang.Issue

@CompileDynamic
class OpenIssuesRegressionFunctionalSpec extends AbstractPitestFunctionalSpec {

    private static final String VALIDATION_ERROR = "without declaring an explicit or implicit dependency"

    @Issue("https://github.com/szpak/gradle-pitest-plugin/issues/147")
    void "should resolve Kotlin JVM library dependency which was not built yet"() {
        given:
            writeRootBuildFile()
            settingsFile << "include ':app', ':kotlin'\n"
            createFile('kotlin/build.gradle') << """
                apply plugin: 'org.jetbrains.kotlin.jvm'
                kotlin {
                    jvmToolchain(17)
                }
            """.stripIndent()
            createFile('kotlin/src/main/kotlin/pitest/lib/Greeter.kt') << """
                package pitest.lib

                class Greeter {
                    fun isPositive(value: Int) = value > 0
                }
            """.stripIndent()
            createFile('app/build.gradle') << androidModule('com.android.application', 'pitest.app') + """
                dependencies {
                    implementation project(':kotlin')
                    testImplementation 'junit:junit:4.13.2'
                }
                pitest {
                    targetClasses = ['pitest.**']
                }
            """.stripIndent()
            writeAppClassUsingLibrary()
        and:
            assert !fileExists('kotlin/build/libs')
        when:
            ExecutionResult result = runTasksSuccessfully(':app:pitestDebug')
        then:
            result.wasExecuted(':app:pitestDebug')
            result.standardOutput.contains('Generated 2 mutations Killed 2 (100%)')
    }

    @Issue("https://github.com/szpak/gradle-pitest-plugin/issues/92")
    void "should fail with a clear message when applied to a non-Android module"() {
        given:
            writeRootBuildFile()
            buildFile << """
                subprojects {
                    apply plugin: 'pl.droidsonroids.pitest'
                }
            """.stripIndent()
            settingsFile << "include ':app', ':kotlin'\n"
            createFile('kotlin/build.gradle') << "apply plugin: 'org.jetbrains.kotlin.jvm'\n"
            createFile('app/build.gradle') << androidModule('com.android.application', 'pitest.app')
        when:
            ExecutionResult result = runTasksWithFailure('tasks')
        then:
            result.standardError.contains("No Android plugin found in project ':kotlin'")
            !result.standardError.contains("Could not get unknown property 'android'")
    }

    @Issue("https://github.com/szpak/gradle-pitest-plugin/issues/146")
    void "should aggregate reports of Kotlin Android modules without validation errors"() {
        given:
            writeRootBuildFile()
            buildFile << """
                apply plugin: 'pl.droidsonroids.pitest.aggregator'
            """.stripIndent()
            settingsFile << "include ':module1', ':module2'\n"
            ['module1', 'module2'].each { String name ->
                writeKotlinAndroidModule(name)
            }
        when:
            ExecutionResult result = runTasks('pitest', 'pitestReportAggregate')
        then:
            !result.standardError.contains(VALIDATION_ERROR)
            result.success
            result.wasExecuted(':pitestReportAggregate')
            fileExists('build/reports/pitest/index.html')
    }

    @Issue("https://github.com/szpak/gradle-pitest-plugin/issues/146")
    void "should aggregate only the reports of the executed debug variant"() {
        given:
            writeAggregatedModules()
        when:
            ExecutionResult result = runTasks('pitestDebug', 'pitestReportAggregate')
        then:
            !result.standardError.contains(VALIDATION_ERROR)
            result.success
            result.wasExecuted(':module1:pitestDebug')
            result.wasExecuted(':pitestReportAggregate')
            !result.wasExecuted(':module1:pitestRelease')
            fileExists('module1/build/reports/pitest/debug/mutations.xml')
            !fileExists('module1/build/reports/pitest/release')
            fileExists('build/reports/pitest/index.html')
    }

    void "should aggregate reports with the configuration cache and reuse the entry"() {
        given:
            writeAggregatedModules()
        when:
            ExecutionResult firstResult = runTasks('pitestDebug', 'pitestReportAggregate', '--configuration-cache')
            ExecutionResult secondResult = runTasks('pitestDebug', 'pitestReportAggregate', '--configuration-cache', '--rerun-tasks')
        then:
            firstResult.success
            firstResult.standardOutput.contains('Configuration cache entry stored.')
            firstResult.standardOutput.contains('0 problems were found storing the configuration cache.')
            secondResult.success
            secondResult.standardOutput.contains('Reusing configuration cache.')
            secondResult.wasExecuted(':pitestReportAggregate')
            fileExists('build/reports/pitest/index.html')
    }

    @Issue("https://github.com/szpak/gradle-pitest-plugin/issues/152")
    void "should run pitest together with assembleRelease in a single Kotlin Android app"() {
        given:
            writeRootBuildFile()
            settingsFile << "include ':app'\n"
            writeKotlinAndroidModule('app', 'com.android.application')
        when:
            ExecutionResult result = runTasks('pitest', 'assembleRelease')
        then:
            !result.standardError.contains(VALIDATION_ERROR)
            result.success
            result.wasExecuted(':app:pitestRelease')
            result.wasExecuted(':app:assembleRelease')
    }

    private void writeAggregatedModules() {
        writeRootBuildFile()
        buildFile << """
            apply plugin: 'pl.droidsonroids.pitest.aggregator'
        """.stripIndent()
        settingsFile << "include ':module1', ':module2'\n"
        ['module1', 'module2'].each { String name ->
            writeKotlinAndroidModule(name)
        }
    }

    private void writeRootBuildFile() {
        buildFile << """
            buildscript {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
                dependencies {
                    classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0'
                    classpath 'com.android.tools.build:gradle:8.5.1'
                }
            }
            allprojects {
                repositories {
                    google()
                    mavenCentral()
                }
            }
        """.stripIndent()
    }

    private static String androidModule(String androidPluginId, String namespace) {
        return """
            apply plugin: '${androidPluginId}'
            apply plugin: 'pl.droidsonroids.pitest'

            android {
                namespace '${namespace}'
                compileSdkVersion 34
                defaultConfig {
                    minSdkVersion 21
                    targetSdkVersion 34
                }
            }
        """.stripIndent()
    }

    private void writeKotlinAndroidModule(String name, String androidPluginId = 'com.android.library') {
        String packageName = "pitest.${name}"
        createFile("${name}/build.gradle") << androidModule(androidPluginId, packageName) + """
            apply plugin: 'org.jetbrains.kotlin.android'

            kotlin {
                jvmToolchain(17)
            }
            dependencies {
                testImplementation 'junit:junit:4.13.2'
            }
            pitest {
                targetClasses = ['pitest.**']
                outputFormats = ["HTML", "XML"]
                timestampedReports = false
                exportLineCoverage = true
            }
        """.stripIndent()
        createFile("${name}/src/main/AndroidManifest.xml") << '<?xml version="1.0" encoding="utf-8"?><manifest />'
        createFile("${name}/src/main/kotlin/pitest/${name}/Counter.kt") << """
            package pitest.${name}

            class Counter(private val value: Int) {
                fun isLessThan(other: Counter) = value < other.value
            }
        """.stripIndent()
        createFile("${name}/src/test/kotlin/pitest/${name}/CounterTest.kt") << """
            package pitest.${name}

            import org.junit.Assert.assertFalse
            import org.junit.Assert.assertTrue
            import org.junit.Test

            class CounterTest {
                @Test
                fun lessThan() {
                    assertTrue(Counter(1).isLessThan(Counter(2)))
                }

                @Test
                fun notLessThan() {
                    assertFalse(Counter(2).isLessThan(Counter(1)))
                }

                @Test
                fun equal() {
                    assertFalse(Counter(1).isLessThan(Counter(1)))
                }
            }
        """.stripIndent()
    }

    private void writeAppClassUsingLibrary() {
        createFile('app/src/main/AndroidManifest.xml') << '<?xml version="1.0" encoding="utf-8"?><manifest />'
        createFile('app/src/main/java/pitest/app/Checker.java') << """
            package pitest.app;

            import pitest.lib.Greeter;

            public class Checker {
                public boolean check(int value) {
                    return new Greeter().isPositive(value);
                }
            }
        """.stripIndent()
        createFile('app/src/test/java/pitest/app/CheckerTest.java') << """
            package pitest.app;

            import org.junit.Test;
            import static org.junit.Assert.assertFalse;
            import static org.junit.Assert.assertTrue;

            public class CheckerTest {
                @Test
                public void positive() {
                    assertTrue(new Checker().check(1));
                }

                @Test
                public void nonPositive() {
                    assertFalse(new Checker().check(0));
                }
            }
        """.stripIndent()
    }

}
