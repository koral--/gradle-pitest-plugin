package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

@CompileDynamic
class MockableAndroidJarFunctionalSpec extends AbstractPitestFunctionalSpec {

    void "should mutate production code using mockable Android JAR"() {
        given:
            buildFile << """
                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                    dependencies {
                        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.21"
                        classpath 'com.android.tools.build:gradle:7.0.0'
                    }
                }

                apply plugin: 'pl.droidsonroids.pitest'
                apply plugin: 'com.android.application'

                android {
                    compileSdkVersion 30
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 30
                    }
                    testOptions {
                        unitTests.returnDefaultValues = true
                    }
                }

                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }

                repositories {
                    google()
                    mavenCentral()
                }

                group = "pitest.test"

                dependencies {
                    testImplementation 'junit:junit:4.13.2'
                }
                """.stripIndent()
        and:
            copyResources("testProjects/mockableAndroidJar", "")
        when:
            ExecutionResult result = runTasksSuccessfully('pitestRelease')
        then:
            result.wasExecuted('pitestRelease')
            result.standardOutput.contains('Generated 1 mutations Killed 1 (100%)')
    }

}
