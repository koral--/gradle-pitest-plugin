package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

@CompileDynamic
class PitestTaskConfigurationCacheFunctionalSpec extends AbstractPitestFunctionalSpec {

    void "should store and reuse configuration cache entry for the Pitest task and run PIT"() {
        given:
            buildFile << getBasicGradlePitestConfig()
            writeHelloPitClass()
            writeHelloPitTest()
        when:
            ExecutionResult firstResult = runTasksSuccessfully("pitestDebug", "--configuration-cache")
        then:
            firstResult.standardOutput.contains("Configuration cache entry stored")
            !firstResult.standardOutput.contains("problem was found storing the configuration cache")
            firstResult.wasExecuted(":pitestDebug")
            firstResult.standardOutput.contains("Generated 2 mutations Killed 1 (50%)")
        when:
            ExecutionResult secondResult = runTasksSuccessfully("pitestDebug", "--configuration-cache", "--rerun-tasks")
        then:
            secondResult.standardOutput.contains("Reusing configuration cache")
            secondResult.wasExecuted(":pitestDebug")
            secondResult.standardOutput.contains("Generated 2 mutations Killed 1 (50%)")
    }

}
