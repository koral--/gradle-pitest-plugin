package pl.droidsonroids.gradle.pitest.functional

import groovy.io.FileType
import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult
import org.junit.Assume

@CompileDynamic
class KotlinPitestPluginFunctionalSpec extends AbstractPitestFunctionalSpec {

    void "setup and run simple build on pitest infrastructure with kotlin plugin"() {
        Assume.assumeFalse("StreamCorruptedException: invalid type code: 03 on Windows", System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT).contains("win"))
        given:
            buildFile << """
                apply plugin: 'com.android.library'
                apply plugin: 'kotlin-android'
                apply plugin: 'pl.droidsonroids.pitest'

                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                    dependencies {
                        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24"
                        classpath 'com.android.tools.build:gradle:8.5.1'
                    }
                }

                android {
                    namespace 'pl.drodsonroids.pitest'
                    compileSdkVersion 30
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 30
                    }
                    lintOptions {
                        //ignore missing lint database
                        abortOnError false
                    }
                }
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    testImplementation 'junit:junit:4.13.2'
                    implementation "org.jetbrains.kotlin:kotlin-stdlib:1.5.21"
                }
            """.stripIndent()
        and:
            writeManifestFile()
        when:
            writeHelloWorld('gradle.pitest.test.hello')
        then:
            fileExists('src/main/java/gradle/pitest/test/hello/HelloWorld.java')
        when:
            writeTest('src/test/java/', 'gradle.pitest.test.hello', false)
        then:
            fileExists('src/test/java/gradle/pitest/test/hello/HelloWorldTest.java')
        when:
            ExecutionResult result = runTasksSuccessfully('build')
        then:
            isFileFound(projectDir, 'HelloWorld.class')
            result.wasExecuted(':test')
    }

    private static boolean isFileFound(File directory, String fileName) {
        def found = false
        directory.traverse(type: FileType.FILES) { file ->
            if (file.name == fileName) {
                found = true
            }
        }
        return found
    }

    void "should run mutation analysis with kotlin Android plugin"() {
        when:
            copyResources("testProjects/simpleKotlin", "")
        then:
            fileExists('build.gradle')
        when:
            ExecutionResult result = runTasksSuccessfully('pitestRelease')
        then:
            result.wasExecuted(':pitestRelease')
            result.standardOutput.contains('Generated 3 mutations Killed 3 (100%)')
    }

}
