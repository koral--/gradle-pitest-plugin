package pl.droidsonroids.gradle.pitest.functional

import pl.droidsonroids.gradle.pitest.PitestPlugin
import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult
import org.junit.Rule
import spock.lang.Ignore
import org.junit.rules.TemporaryFolder
import spock.lang.Issue

@CompileDynamic
class PitestPluginGeneralFunctionalSpec extends AbstractPitestFunctionalSpec {

    @Rule
    protected TemporaryFolder tmpDir = new TemporaryFolder()

    @Ignore("Bintray not accessible")
    void "enable PIT plugin when on classpath and pass plugin configuration to PIT"() {
        given:
            buildFile << getBasicGradlePitestConfig()
            buildFile << """
                rootProject.buildscript {
                    repositories {
                        maven { url "https://dl.bintray.com/szpak/pitest-plugins/" }
                    }
                    dependencies {
                        pitest 'org.pitest.plugins:pitest-plugin-configuration-reporter-plugin:0.0.2'
                    }
                }
                pitest {
                    excludedClasses = []
                    verbose = true
                    pluginConfiguration = ['pitest-plugin-configuration-reporter-plugin.key1': 'value1',
                                           'pitest-plugin-configuration-reporter-plugin.key2': 'value2']
                    features = ["-FANN", "+FINFIT(a[1] a[2])"]
                    outputFormats = ['pluginConfigurationReporter']
                }
            """.stripIndent()
        and:
            writeHelloPitClass()
            writeHelloPitTest()
        when:
            ExecutionResult result = runTasksSuccessfully('pitestRelease')
        then:
            result.wasExecuted(':pitestRelease')
        and: 'plugin enabled'
            assertStdOutOrStdErrContainsGivenText(result, 'with the following plugin configuration')
        and: 'plugin parameters passed'
            result.getStandardOutput().contains('pitest-plugin-configuration-reporter-plugin.key1=value1')
            result.getStandardOutput().contains('pitest-plugin-configuration-reporter-plugin.key2=value2')
        and: 'built-in features passed'
            result.getStandardOutput().contains("-FANN")
            result.getStandardOutput().contains("+FINFIT")
        and: 'verbose output enabled'
            assertStdOutOrStdErrContainsGivenText(result, "PIT >> FINE")
            //TODO: Add plugin features once available - https://github.com/hcoles/pitest-plugins/issues/2
    }

    @Issue(["https://github.com/gradle/gradle/issues/12351", "https://github.com/szpak/gradle-pitest-plugin/issues/189"])
    void "allow to use RegularFileProperty @Input and @Output fields in task"() {
        given:
            File historyInputLocation = tmpDir.newFile()
            File historyOutputLocation = tmpDir.newFile()
        and:
            buildFile << getBasicGradlePitestConfig()
            buildFile << """
                pitest {
                    historyInputLocation = "${quoteBackslashesInWindowsPath(historyInputLocation)}"
                    historyOutputLocation = "${quoteBackslashesInWindowsPath(historyOutputLocation)}"
                }
            """.stripIndent()
        and:
            writeHelloPitClass()
            writeHelloPitTest()
        when:
            ExecutionResult result = runTasksSuccessfully('pitest')
        then:
            result.wasExecuted(':pitest')
            result.getStandardOutput().contains("--historyInputLocation=${historyInputLocation.absolutePath}")
        and:    //it works with @OutputFile by default, but just in case
            result.getStandardOutput().contains("--historyOutputLocation=${historyOutputLocation.absolutePath}")
            historyOutputLocation.size()
    }

    void "pass additional configured parameters that cannot be test with ProjectBuilder"() {
        given:
            buildFile << getBasicGradlePitestConfig()
            buildFile << """
                pitest {
                    timestampedReports = false  //to do not mess with file path on source code in report verification
                    useClasspathFile = true
                    mainProcessJvmArgs = ["-XX:+UnlockExperimentalVMOptions"]
                }
            """.stripIndent()
        and:
            writeHelloPitClass()
            writeHelloPitTest()
        when:
            ExecutionResult result = runTasksSuccessfully('pitest')
        then:
            result.wasExecuted(':pitest')
        and:
            result.standardOutput.contains('Generated 2 mutations Killed 1 (50%)')
            result.standardOutput.contains('Ran 2 tests (1 tests per mutation)')

        and: "use file to pass additional classpath to PIT if enabled"  //Needed? Already tested with ProjectBuilder in PitestTaskConfigurationSpec
            result.getStandardOutput().contains(
                "--classPathFile=${new File(projectDir, "build//${PitestPlugin.PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME}").absolutePath}")
            !result.getStandardOutput().find("--classPath=")

        and: "use defined mainProcessJvmArgs to run PIT main process"
            result.getStandardOutput().matches(/(?m)[\s\S]*java(.exe)* -XX:\+UnlockExperimentalVMOptions[\s\S]*/)

        and: "has source code available in report"
            File htmlFileWithReportForHelloPit = new File(projectDir, "build//reports//pitest//gradle.pitest.test.hello//HelloPit.java.html")
            htmlFileWithReportForHelloPit.text.contains("System.out.println(&#34;Mutation to survive&#34;);")
    }

    void "allow override report directory"() {
        given:
        buildFile << """
                apply plugin: 'com.android.library'
                apply plugin: 'pl.droidsonroids.pitest'

                android {
                    compileSdkVersion 30
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 30
                    }
                }
                group = 'gradle.pitest.test'

                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                    dependencies {
                        classpath 'com.android.tools.build:gradle:7.0.0'
                    }
                }
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies { testImplementation 'junit:junit:4.13.2' }

                pitest {
                    reportDir = file("build/pitest-reports")
                }
            """.stripIndent()
        and:
        writeHelloWorld('gradle.pitest.test.hello')
        when:
        ExecutionResult result = runTasksSuccessfully('pitestRelease')
        then:
        result.standardOutput.contains('Generated 1 mutations Killed 0 (0%)')
        fileExists('build/pitest-reports')
    }

    private String quoteBackslashesInWindowsPath(File file) {
        //There is problem with backslash within '' or "" while running this test on Windows: "unexpected char"
        return file.absolutePath.replaceAll('\\\\', '\\\\\\\\')
    }

}
