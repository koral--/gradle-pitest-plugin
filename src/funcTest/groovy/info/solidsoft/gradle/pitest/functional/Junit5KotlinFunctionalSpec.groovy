package info.solidsoft.gradle.pitest.functional

import nebula.test.functional.ExecutionResult

class Junit5KotlinFunctionalSpec extends AbstractPitestFunctionalSpec {

    def "should work with kotlin and junit5"() {
        given:
            copyResources("testProjects/junit5", "")
        when:
            ExecutionResult result = runTasksSuccessfully('pitest')
        then:
            result.wasExecuted('pitest')
            result.getStandardOutput().contains('Generated 2 mutations Killed 2 (100%)')
    }
}
