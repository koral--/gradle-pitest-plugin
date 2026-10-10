package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

@CompileDynamic
class PitestTaskDependenciesFunctionalSpec extends AbstractPitestFunctionalSpec {

    void "should schedule compilation of main and unit test sources before pitest task for Java"() {
        given:
            buildFile << basicGradlePitestConfig
            writeHelloPitClass()
            writeHelloPitTest()
        when:
            ExecutionResult result = runTasksSuccessfully("pitestDebug", "--dry-run")
        then:
            List<String> tasks = scheduledTasks(result)
            tasks.containsAll([":compileDebugJavaWithJavac", ":compileDebugUnitTestJavaWithJavac", ":compileDebugUnitTestSources"])
            tasks.indexOf(":compileDebugUnitTestSources") < tasks.indexOf(":pitestDebug")
            tasks.indexOf(":compileDebugJavaWithJavac") < tasks.indexOf(":pitestDebug")
    }

    void "should schedule compilation of main and unit test sources before pitest task for Kotlin"() {
        given:
            copyResources("testProjects/simpleKotlin", "")
        when:
            ExecutionResult result = runTasksSuccessfully("pitestRelease", "--dry-run")
        then:
            List<String> tasks = scheduledTasks(result)
            tasks.containsAll([":compileReleaseKotlin", ":compileReleaseUnitTestKotlin", ":compileReleaseUnitTestSources"])
            tasks.indexOf(":compileReleaseUnitTestSources") < tasks.indexOf(":pitestRelease")
            tasks.indexOf(":compileReleaseKotlin") < tasks.indexOf(":pitestRelease")
    }

    private static List<String> scheduledTasks(ExecutionResult result) {
        return result.standardOutput.readLines()
            .findAll { String line -> line.startsWith(":") }
            .collect { String line -> line.split(" ")[0] }
    }

}
