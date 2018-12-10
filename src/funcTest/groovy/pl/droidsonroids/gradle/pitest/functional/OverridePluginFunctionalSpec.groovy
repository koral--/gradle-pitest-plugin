package pl.droidsonroids.gradle.pitest.functional

import nebula.test.functional.ExecutionResult

//Note: gradle-override-plugin has important limitations in support for collections
//See: https://github.com/nebula-plugins/gradle-override-plugin/issues/1 or https://github.com/nebula-plugins/gradle-override-plugin/issues/3
class OverridePluginFunctionalSpec extends AbstractPitestFunctionalSpec {

    def "should allow to override String configuration parameter from command line with AGP #requestedAndroidGradlePluginVersion"() {
        given:
            buildFile << """
                apply plugin: 'nebula-override'
                apply plugin: 'com.android.library'
                apply plugin: 'pl.droidsonroids.pitest'

                android {
                    compileSdkVersion 28
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 28
                    }
                }
                group = 'gradle.pitest.test'

                buildscript {
                    repositories { 
                        jcenter()
                        google()
                    }
                    dependencies {
                        classpath 'com.netflix.nebula:gradle-override-plugin:1.12.+'
                        classpath 'com.android.tools.build:gradle:$requestedAndroidGradlePluginVersion'
                    }
                }
                repositories {
                    google() 
                    mavenCentral() 
                }
                dependencies { testImplementation 'junit:junit:4.12' }
            """.stripIndent()
        and:
            writeHelloWorld('gradle.pitest.test.hello')
        when:
            ExecutionResult result = runTasksSuccessfully('pitestRelease', '-Doverride.pitest.reportDir=build/treports')
        then:
            result.standardOutput.contains('Generated 1 mutations Killed 0 (0%)')
            fileExists('build/treports')
        where:
            requestedAndroidGradlePluginVersion << resolveRequestedAndroidGradlePluginVersion()
    }
}
