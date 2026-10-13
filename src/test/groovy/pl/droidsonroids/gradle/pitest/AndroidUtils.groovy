package pl.droidsonroids.gradle.pitest

import groovy.transform.CompileDynamic
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

@CompileDynamic
@SuppressWarnings(["DuplicateNumberLiteral", "DuplicateMapLiteral"])
class AndroidUtils {

    static final String PITEST_RELEASE_TASK_NAME = "${PitestPlugin.PITEST_TASK_NAME}Release"

    /**
     * ProjectBuilder using the Gradle user home shared by all unit tests (set by the "test" task).
     * Without it every test gets a fresh one and downloads the same artifacts (e.g. pitestTestCompile POMs) again,
     * which gets the machine rate limited by Maven Central.
     */
    static ProjectBuilder projectBuilder() {
        ProjectBuilder builder = ProjectBuilder.builder()
        String sharedUserHome = System.getProperty('projectBuilder.gradleUserHome')
        if (sharedUserHome) {
            builder.withGradleUserHomeDir(new File(sharedUserHome))
        }
        return builder
    }

    static Project createSampleLibraryProject(File... rootDir) {
        return createSampleLibraryProject(false, rootDir)
    }

    static Project createSampleLibraryProject(boolean applyPitestFirst, File... rootDir) {
        ProjectBuilder builder = projectBuilder()
        if (rootDir.length > 0) {
            builder.withProjectDir(rootDir[0])
        }
        Project project = builder.build()
        ClassLoader classLoader = AndroidUtils.classLoader
        URL resource = classLoader.getResource('lib/AndroidManifest.xml')
        File manifestFile = project.file('src/main/AndroidManifest.xml')
        manifestFile.parentFile.mkdirs()
        manifestFile.write(resource.text)
        project.repositories {
            google()
            mavenCentral()
        }
        if (applyPitestFirst) {
            project.apply(plugin: "pl.droidsonroids.pitest")
        }
        project.apply(plugin: "com.android.library")
        project.android.with {
            namespace 'pl.drodsonroids.pitest'
            compileSdkVersion 30
            defaultConfig {
                minSdkVersion 10
                targetSdkVersion 30
            }
        }
        if (!applyPitestFirst) {
            project.apply(plugin: "pl.droidsonroids.pitest")
        }
        return project
    }

    static Project createSampleApplicationProject(File... rootDir) {
        return createSampleApplicationProject(false, rootDir)
    }

    static Project createSampleApplicationProject(boolean applyPitestFirst, File... rootDir) {
        ProjectBuilder builder = projectBuilder()
        if (rootDir.length > 0) {
            builder.withProjectDir(rootDir[0])
        }
        Project project = builder.build()
        ClassLoader classLoader = AndroidUtils.classLoader
        URL resource = classLoader.getResource('app/AndroidManifest.xml')
        File manifestFile = project.file('src/main/AndroidManifest.xml')
        manifestFile.parentFile.mkdirs()
        manifestFile.write(resource.text)
        project.buildscript.repositories {
            google()
            mavenCentral()
        }
        if (applyPitestFirst) {
            project.apply(plugin: "pl.droidsonroids.pitest")
        }
        project.apply(plugin: "com.android.application")
        project.android.with {
            namespace 'pl.drodsonroids.pitest'
            compileSdkVersion 30
            defaultConfig {
                minSdkVersion 10
                targetSdkVersion 30
            }
            buildTypes {
                release { }
                debug { }
            }
            productFlavors {
                flavorDimensions 'tier', 'color'
                free {
                    dimension 'tier'
                }
                pro {
                    dimension 'tier'
                }
                blue {
                    dimension 'color'
                }
                red {
                    dimension 'color'
                }
            }
            testOptions {
                unitTests.returnDefaultValues = true
            }
        }
        project.dependencies {
            implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
            testImplementation("io.mockk:mockk:1.11.0")
        }
        project.repositories {
            mavenCentral()
            google()
        }
        if (!applyPitestFirst) {
            project.apply(plugin: "pl.droidsonroids.pitest")
        }
        return project
    }

}
