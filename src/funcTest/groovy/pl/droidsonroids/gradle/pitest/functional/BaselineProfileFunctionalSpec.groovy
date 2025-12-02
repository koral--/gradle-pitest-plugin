package pl.droidsonroids.gradle.pitest.functional

import groovy.io.FileType
import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult
import org.junit.Assume

@CompileDynamic
class BaselineProfileFunctionalSpec extends AbstractPitestFunctionalSpec {

    void "setup and run simple build on pitest infrastructure with baseline plugin"() {
        Assume.assumeFalse("StreamCorruptedException: invalid type code: 03 on Windows", System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT).contains("win"))
        given:
            buildFile << """
                apply plugin: 'com.android.application'
                apply plugin: 'androidx.baselineprofile'
                apply plugin: 'pl.droidsonroids.pitest'

                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'com.android.tools.build:gradle:8.5.1'
                        classpath 'androidx.baselineprofile:androidx.baselineprofile.gradle.plugin:1.4.1'
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
                    //targetProjectPath ':app'
                }

                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    testImplementation 'junit:junit:4.13.2'
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

    void "setup and run simple build on pitest infrastructure with baseline plugin with product flavors"() {
        Assume.assumeFalse("StreamCorruptedException: invalid type code: 03 on Windows", System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT).contains("win"))
        given:
            buildFile << """
                apply plugin: 'com.android.application'
                apply plugin: 'androidx.baselineprofile'
                apply plugin: 'pl.droidsonroids.pitest'

                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'com.android.tools.build:gradle:8.5.1'
                        classpath 'androidx.baselineprofile:androidx.baselineprofile.gradle.plugin:1.4.1'
                    }
                }

                android {
                    namespace 'pl.drodsonroids.pitest'
                    compileSdkVersion 30
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 30
                    }
                    flavorDimensions += "version"
                    productFlavors {
                        create("demo") {
                            dimension = "version"
                            applicationIdSuffix = ".demo"
                            versionNameSuffix = "-demo"
                        }
                        create("full") {
                            dimension = "version"
                            applicationIdSuffix = ".full"
                            versionNameSuffix = "-full"
                        }
                    }
                    lintOptions {
                        //ignore missing lint database
                        abortOnError false
                    }
                    //targetProjectPath ':app'
                }

                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    testImplementation 'junit:junit:4.13.2'
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
        boolean found = false
        directory.traverse(type: FileType.FILES) { file ->
            if (file.name == fileName) {
                found = true
            }
        }
        return found
    }

}
