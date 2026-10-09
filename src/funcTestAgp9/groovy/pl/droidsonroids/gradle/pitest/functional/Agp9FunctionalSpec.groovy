package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

/**
 * Runs on a real AGP 9 (see the {@code funcTestAgp9} task in build.gradle), so that the new DSL code path
 * in {@code PitestPlugin} is exercised against the genuine Variant API instead of AGP 8 with a faked flag.
 */
@CompileDynamic
class Agp9FunctionalSpec extends AbstractPitestFunctionalSpec {

    //https://developer.android.com/build/releases/about-agp - AGP 9.4 requires Gradle 9.6.0 (minimum and default)
    private static final String GRADLE_VERSION_FOR_AGP9 = '9.6.0'
    private static final String AGP_VERSION_MARKER = 'AGP_VERSION_MARKER='
    private static final String WARNING_MODE_ALL = '--warning-mode=all'

    void setup() {
        gradleVersion = GRADLE_VERSION_FOR_AGP9
    }

    void "should execute PIT with mockable android.jar on library with AGP 9"() {
        given:
            writeAndroidLibraryBuildFile()
            copyResources('testProjects/mockableAndroidJar', '')
        when:
            ExecutionResult result = runTasksSuccessfully('pitestDebug', WARNING_MODE_ALL)
        then:
            assertAgp9Loaded(result)
            result.wasExecuted(':pitestMockableAndroidJar')
            result.wasExecuted(':pitestDebug')
            result.standardOutput.contains('Generated 1 mutations Killed 1 (100%)')
            assertNoPluginDeprecations(result, 'library')
    }

    void "should create pitest tasks only for variants with unit tests in application with flavors on AGP 9"() {
        given:
            buildFile << """
                apply plugin: 'com.android.application'
                apply plugin: 'pl.droidsonroids.pitest'
                println "${AGP_VERSION_MARKER}" + com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION

                android {
                    namespace = 'pl.drodsonroids.pitest'
                    compileSdk = 34
                    defaultConfig {
                        minSdk = 21
                        targetSdk = 34
                    }
                    flavorDimensions += 'tier'
                    productFlavors {
                        free { dimension = 'tier' }
                        paid { dimension = 'tier' }
                    }
                }
                ${getCommonBuildFilePart('gradle.pitest.test')}
                dependencies {
                    testImplementation 'junit:junit:4.13.2'
                }
            """.stripIndent()
            writeHelloPitClass()
            writeHelloPitTest()
        when:
            ExecutionResult tasksResult = runTasksSuccessfully('tasks', '--all', WARNING_MODE_ALL)
        then:
            assertAgp9Loaded(tasksResult)
            tasksResult.standardOutput.contains('pitestFreeDebug')
            tasksResult.standardOutput.contains('pitestPaidDebug')
            !tasksResult.standardOutput.contains('pitestFreeRelease')
            !tasksResult.standardOutput.contains('pitestPaidRelease')
        when:
            ExecutionResult result = runTasksSuccessfully('pitest', WARNING_MODE_ALL)
        then:
            result.wasExecuted(':pitestFreeDebug')
            result.wasExecuted(':pitestPaidDebug')
            result.standardOutput.contains('Generated 2 mutations Killed 1 (50%)')
            assertNoPluginDeprecations(result, 'application with flavors')
    }

    void "should mutate Kotlin classes with AGP 9 built-in Kotlin"() {
        given:
            buildFile << """
                apply plugin: 'com.android.library'
                apply plugin: 'pl.droidsonroids.pitest'
                println "${AGP_VERSION_MARKER}" + com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION

                android {
                    namespace = 'pl.drodsonroids.pitest'
                    compileSdk = 34
                    defaultConfig {
                        minSdk = 21
                    }
                }
                ${getCommonBuildFilePart('pitest.test')}
                dependencies {
                    testImplementation 'junit:junit:4.13.2'
                    testImplementation 'org.assertj:assertj-core:3.26.3'
                }
            """.stripIndent()
            copyResources('testProjects/simpleKotlin/src', 'src')
        when:
            ExecutionResult result = runTasksSuccessfully('pitestDebug', WARNING_MODE_ALL)
        then:
            assertAgp9Loaded(result)
            !buildFile.text.contains('kotlin-android')
            result.wasExecuted(':compileDebugKotlin')
            result.wasExecuted(':pitestDebug')
            result.standardOutput.contains('Generated 3 mutations Killed 3 (100%)')
            fileExists('build/reports/pitest/debug/pitest.test/Counter.kt.html')
            assertNoPluginDeprecations(result, 'built-in Kotlin')
    }

    void "should run PIT for Kotlin Multiplatform Android library with host tests on AGP 9"() {
        given:
            buildFile << """
                apply plugin: 'org.jetbrains.kotlin.multiplatform'
                apply plugin: 'com.android.kotlin.multiplatform.library'
                apply plugin: 'pl.droidsonroids.pitest'
                println "${AGP_VERSION_MARKER}" + com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION

                kotlin {
                    androidLibrary {
                        namespace = 'pl.drodsonroids.pitest'
                        compileSdk = 34
                        minSdk = 21
                        withHostTestBuilder {}
                    }
                    sourceSets {
                        androidHostTest {
                            dependencies {
                                implementation 'junit:junit:4.13.2'
                                implementation 'org.jetbrains.kotlin:kotlin-test-junit:2.4.21'
                            }
                        }
                    }
                }
                ${getCommonBuildFilePart('pitest.test')}
            """.stripIndent()
            createFile('src/commonMain/kotlin/pitest/test/Counter.kt') << """
                package pitest.test

                class Counter(private val value: Int) {
                    fun isLessThan(otherCounter: Counter) = this.value < otherCounter.value
                }
            """.stripIndent()
            createFile('src/androidHostTest/kotlin/pitest/test/CounterTest.kt') << """
                package pitest.test

                import org.junit.Test
                import kotlin.test.assertFalse
                import kotlin.test.assertTrue

                class CounterTest {
                    @Test fun smaller() = assertTrue(Counter(1).isLessThan(Counter(2)))
                    @Test fun greater() = assertFalse(Counter(2).isLessThan(Counter(1)))
                    @Test fun same() = assertFalse(Counter(1).isLessThan(Counter(1)))
                }
            """.stripIndent()
        when:
            ExecutionResult result = runTasksSuccessfully('pitestAndroidMain', WARNING_MODE_ALL)
        then:
            assertAgp9Loaded(result)
            result.wasExecuted(':pitestAndroidMain')
            fileExists('build/reports/pitest/androidMain/pitest.test/Counter.kt.html')
            result.standardOutput.contains('Generated 3 mutations Killed 3 (100%)')
            assertNoPluginDeprecations(result, 'KMP')
    }

    void "should work with configuration cache and reuse it on AGP 9"() {
        given:
            writeAndroidLibraryBuildFile()
            copyResources('testProjects/mockableAndroidJar', '')
        when:
            ExecutionResult first = runTasksSuccessfully('pitestDebug', '--configuration-cache', WARNING_MODE_ALL)
        then:
            assertAgp9Loaded(first)
            first.standardOutput.contains('Configuration cache entry stored.')
            !first.standardOutput.contains('problem')
            first.standardOutput.contains('Generated 1 mutations Killed 1 (100%)')
            assertNoPluginDeprecations(first, 'configuration cache, first run')
        when:
            ExecutionResult second = runTasksSuccessfully('pitestDebug', '--configuration-cache', '--rerun-tasks', WARNING_MODE_ALL)
        then:
            second.standardOutput.contains('Reusing configuration cache.')
            !second.standardOutput.contains('problem')
            second.wasExecuted(':pitestMockableAndroidJar')
            second.standardOutput.contains('Generated 1 mutations Killed 1 (100%)')
            assertNoPluginDeprecations(second, 'configuration cache, second run')
    }

    private void writeAndroidLibraryBuildFile() {
        buildFile << """
            apply plugin: 'com.android.library'
            apply plugin: 'pl.droidsonroids.pitest'
            println "${AGP_VERSION_MARKER}" + com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION

            android {
                namespace = 'pl.drodsonroids.pitest'
                compileSdk = 34
                defaultConfig {
                    minSdk = 21
                }
                testOptions {
                    unitTests.returnDefaultValues = true
                }
            }
            ${getCommonBuildFilePart('pitest.test')}
            dependencies {
                testImplementation 'junit:junit:4.13.2'
                testImplementation 'org.json:json:20180813'
            }
        """.stripIndent()
    }

    private static String getCommonBuildFilePart(String group) {
        return """
            group = '${group}'
            repositories {
                google()
                mavenCentral()
            }
        """.stripIndent()
    }

    private static void assertAgp9Loaded(ExecutionResult result) {
        String expectedMajor = System.getProperty('expectedAgpMajor')
        assert expectedMajor == '9': "Run through the funcTestAgp9 task, expectedAgpMajor=${expectedMajor}"
        String line = result.standardOutput.readLines().find { String outputLine -> outputLine.contains(AGP_VERSION_MARKER) }
        assert line != null: 'AGP version was not printed'
        String version = line.substring(line.indexOf(AGP_VERSION_MARKER) + AGP_VERSION_MARKER.length()).trim()
        assert version.startsWith("${expectedMajor}."): "Expected AGP ${expectedMajor}.x but was ${version}"
    }

    /**
     * Fails only on deprecation warnings attributable to this plugin, deprecations from AGP/KGP are tolerated.
     * All deprecations are appended to build/reports/agp9-deprecations.txt for the record.
     */
    private static void assertNoPluginDeprecations(ExecutionResult result, String scenario) {
        List<String> lines = (result.standardOutput + '\n' + result.standardError).readLines()
        List<String> blocks = []
        lines.eachWithIndex { String line, int index ->
            if (line.toLowerCase(Locale.ROOT).contains('deprecated') && !line.startsWith('Deprecated Gradle features were used')) {
                List<String> block = [line]
                for (int i = index + 1; i < lines.size() && block.size() < 40; i++) {
                    String next = lines[i]
                    if (next.isEmpty() || !(next.startsWith(' ') || next.startsWith('\t') || next.startsWith('at '))) {
                        break
                    }
                    block << next
                }
                blocks << block.join('\n')
            }
        }
        File report = new File('build/reports/agp9-deprecations.txt')
        report.parentFile.mkdirs()
        report << "[${scenario}] ${blocks.size()} deprecation(s)\n"
        blocks.each { String block -> report << "[${scenario}] ${block}\n---\n" }
        List<String> ours = blocks.findAll { String block -> block.contains('pl.droidsonroids') }
        assert ours.isEmpty(): "Deprecations attributable to the plugin:\n${ours.join('\n---\n')}"
    }

}
