package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.IntegrationSpec
import nebula.test.functional.ExecutionResult

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@CompileDynamic
@SuppressWarnings("AbstractClassWithoutAbstractMethod")
abstract class AbstractPitestFunctionalSpec extends IntegrationSpec {

    void setup() {
        fork = true
        //to make stdout assertion work with Gradle 2.x - http://forums.gradle.org/gradle/topics/unable-to-catch-stdout-stderr-when-using-tooling-api-i-gradle-2-x#reply_15357743
        memorySafeMode = true   //shutdown Daemon after a few seconds of inactivity
        copyResources('AndroidManifest.xml', 'src/main/AndroidManifest.xml')
    }

    void writeManifestFile() {
        File manifestFile = new File(projectDir, 'src/main/AndroidManifest.xml')
        manifestFile.parentFile.mkdirs()
        manifestFile.write('<?xml version="1.0" encoding="utf-8"?><manifest />')
    }

    protected static String getBasicGradlePitestConfig() {
        return """
                apply plugin: 'com.android.library'
                apply plugin: 'pl.droidsonroids.pitest'

                android {
                    namespace 'pl.drodsonroids.pitest'
                    compileSdkVersion 34
                    defaultConfig {
                        minSdkVersion 10
                        targetSdkVersion 33
                    }
                }
                group = 'gradle.pitest.test'

                repositories {
                    google()
                    mavenCentral()
                }
                buildscript {
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                dependencies {
                    testImplementation 'junit:junit:4.13.2'
                }
        """.stripIndent()
    }

    protected void writeHelloPitClass(String packageDotted = 'gradle.pitest.test.hello', File baseDir = getProjectDir()) {
        String path = 'src/main/java/' + packageDotted.replace('.', '/') + '/HelloPit.java'
        File javaFile = createFile(path, baseDir)
        javaFile << """package ${packageDotted};

            public class HelloPit {
                public int returnInputNumber(int inputNumber) {
                    System.out.println("Mutation to survive");
                    return inputNumber;
                }
            }
        """.stripIndent()
    }

    protected void writeHelloPitTest(String packageDotted = 'gradle.pitest.test.hello', File baseDir = getProjectDir()) {
        String path = 'src/test/java/' + packageDotted.replace('.', '/') + '/HelloPitTest.java'
        File javaFile = createFile(path, baseDir)
        javaFile << """package ${packageDotted};
            import org.junit.Test;
            import static org.junit.Assert.assertEquals;

            public class HelloPitTest {
                @Test public void shouldReturnInputNumber() {
                    assertEquals(5, new HelloPit().returnInputNumber(5));
                }
            }
        """.stripIndent()
    }

    protected void assertStdOutOrStdErrContainsGivenText(ExecutionResult result, String textToContain) {
        //TODO: Simplify if possible - standardOutput for Gradle <5 and standardError for Gradle 5+
        assert result.standardOutput.contains(textToContain) || result.standardError.contains(textToContain)
    }

    //TODO: Switch to Gradle mechanism once upgrade to 6.x
    protected boolean isJava13Compatible() {
        return System.getProperty("java.version").startsWith("13") || isJava14Compatible()
    }

    protected boolean isJava14Compatible() {
        return System.getProperty("java.version").startsWith("14") || System.getProperty("java.version").startsWith("15")
    }

    //Due to deprecated "-b build-foo.gradle" - https://docs.gradle.org/7.6.2/userguide/upgrading_version_7.html#configuring_custom_build_layout
    protected void deleteExistingOrFail(String path, File baseDir = getProjectDir()) {
        Files.delete(new File(baseDir, path).toPath())
    }

    protected void renameExistingFileToBuildGradle(String path, File baseDir = getProjectDir()) {
        Path sourceFile = new File(baseDir, path).toPath()
        Files.move(sourceFile, sourceFile.resolveSibling("build.gradle"), StandardCopyOption.REPLACE_EXISTING)
    }

    protected void renameExistingFailToSettingsGradle(String path, File baseDir = getProjectDir()) {
        Path sourceFile = new File(baseDir, path).toPath()
        Files.move(sourceFile, sourceFile.resolveSibling("settings.gradle"), StandardCopyOption.REPLACE_EXISTING)
    }

}
