package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

@CompileDynamic
class AggregatorConfigurationCacheFunctionalSpec extends AbstractPitestFunctionalSpec {

    //PitestTask itself is not configuration cache compatible yet, so only the aggregator plugin is checked
    void "should store and reuse configuration cache entry for aggregate report task"() {
        given:
            buildFile << """
                apply plugin: 'pl.droidsonroids.pitest.aggregator'

                repositories {
                    mavenCentral()
                }
            """.stripIndent()
        when:
            ExecutionResult firstResult = runTasksSuccessfully("pitestReportAggregate", "--configuration-cache")
        then:
            firstResult.standardOutput.contains("Configuration cache entry stored")
            !firstResult.standardOutput.contains("problem was found storing the configuration cache")
        when:
            ExecutionResult secondResult = runTasksSuccessfully("pitestReportAggregate", "--configuration-cache")
        then:
            secondResult.standardOutput.contains("Reusing configuration cache")
    }

}
