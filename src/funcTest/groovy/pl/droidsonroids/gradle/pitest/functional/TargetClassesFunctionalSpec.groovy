package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

@CompileDynamic
class TargetClassesFunctionalSpec extends AbstractPitestFunctionalSpec {

    void "report error when no targetClasses parameter is defined"() {
        given:
        buildFile << """
                apply plugin: 'com.android.library'
                apply plugin: 'pl.droidsonroids.pitest'

                repositories {
                    google()
                }

                android {
                    compileSdkVersion 30
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 30
                    }
                }
            """.stripIndent()
        and:
        writeHelloWorld('gradle.pitest.test.hello')
        when:
        ExecutionResult result = runTasksWithFailure('pitestRelease')
        then:
        assertStdOutOrStdErrContainsGivenText(result, "No value has been specified for property 'targetClasses'")
    }

}
