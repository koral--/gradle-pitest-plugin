/* Copyright (c) 2012 Marcin Zajączkowski
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.droidsonroids.gradle.pitest

import groovy.transform.CompileDynamic
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import spock.lang.Issue
import spock.lang.Specification

@CompileDynamic
class PitestPluginTest extends Specification {

    static void assertThatTasksAreInGroup(Project project, List<String> taskNames, String group) {
        taskNames.each { String taskName ->
            Task task = project.tasks[taskName]
            assert task != null
            assert task.group == group
        }
    }

    //the dependencies of variant tasks are lazy (closures), so `Task.getDependsOn()` does not list them by name
    private static Set<String> dependencyNames(Task task) {
        return task.taskDependencies.getDependencies(task)*.name as Set
    }

    @Issue('https://github.com/szpak/gradle-pitest-plugin/issues/390')
    void "add junit-platform-launcher based on direct test dependencies without creating helper configuration"() {
        given:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.0")
        when:
            project.evaluate()
            project.configurations.getByName("debugUnitTestRuntimeClasspath").incoming.resolutionResult.allComponents  //triggers withDependencies
        then:
            project.configurations.getByName("testRuntimeOnly").dependencies.any { dependency ->
                dependency.group == "org.junit.platform" && dependency.name == "junit-platform-launcher"
            }
        and:
            project.configurations.findByName("tmpTestImplementation") == null
    }

    void "add pitest tasks to android library project in proper group"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.evaluate()
        then:
            project.plugins.hasPlugin(PitestPlugin)
            List<String> tasks = [AndroidUtils.PITEST_RELEASE_TASK_NAME, "${PitestPlugin.PITEST_TASK_NAME}Debug"]
            assertThatTasksAreInGroup(project, tasks, PitestPlugin.PITEST_TASK_GROUP)
    }

    @Issue("https://github.com/koral--/gradle-pitest-plugin/issues/116")
    void "excludes mockable Android JAR"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.android {
                compileOptions {
                    sourceCompatibility 11
                    targetCompatibility 11
                }
            }
            project.pitest {
                excludeMockableAndroidJar = true
            }
            project.evaluate()
        then:
            !project.tasks.getByName('pitestRelease').additionalClasspath.getAsPath().contains('android.jar')
    }

    void "apply pitest plugin without android plugin applied"() {
        given:
            Project project = AndroidUtils.projectBuilder().build()
        expect:
            !project.plugins.hasPlugin("com.android.application") &&
                !project.plugins.hasPlugin("com.android.library") &&
                !project.plugins.hasPlugin("com.android.test")
        when:
            project.apply(plugin: "pl.droidsonroids.pitest")
            project.evaluate()
        then:
            thrown(GradleException)
    }

    @SuppressWarnings("ImplicitClosureParameter")
    void "depend on the Android task that copies resources to the build directory (for robolectric, etc)"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.evaluate()
        then:
            assert dependencyNames(project.tasks[AndroidUtils.PITEST_RELEASE_TASK_NAME]).contains('compileReleaseUnitTestSources')
            assert dependencyNames(project.tasks["${PitestPlugin.PITEST_TASK_NAME}Debug"]).contains('compileDebugUnitTestSources')
    }

    @SuppressWarnings("ImplicitClosureParameter")
    void "depend on the Android application task that copies resources to the build directory (for robolectric, etc)"() {
        when:
            Project project = AndroidUtils.createSampleApplicationProject()
            project.evaluate()
        then:
            assert dependencyNames(project.tasks["${PitestPlugin.PITEST_TASK_NAME}FreeBlueRelease"]).contains('compileFreeBlueReleaseUnitTestSources')
    }

    @SuppressWarnings("ImplicitClosureParameter")
    void "combined task classpath contains correct variant paths for flavored application projects"() {
        when:
            Project project = AndroidUtils.createSampleApplicationProject()
            project.evaluate()
        then:
            Object classpath = project.tasks["${PitestPlugin.PITEST_TASK_NAME}FreeBlueRelease"].additionalClasspath.files
            assert classpath.find { it.toString().endsWith("sourceFolderJavaResources${File.separator}freeBlue${File.separator}release") }
            assert classpath.find { it.toString().endsWith("sourceFolderJavaResources${File.separator}test${File.separator}freeBlue${File.separator}release") }
    }

    @SuppressWarnings("ImplicitClosureParameter")
    void "combined task classpath contains dependencies in correct order"() {
        when:
            Project project = AndroidUtils.createSampleApplicationProject()
            project.evaluate()
        then:
            Set<File> classpath = project.tasks["${PitestPlugin.PITEST_TASK_NAME}FreeBlueRelease"].additionalClasspath.files
            assert classpath.find { it.toString().endsWith('kotlin-reflect-1.3.72.jar') } == null
            assert classpath.find { it.toString().endsWith('kotlin-reflect-1.6.10.jar') }
    }

    void "strange sdk versions get properly sanitized"() {
        when:
            String version = PitestPlugin.sanitizeSdkVersion('strange version (0)')
        then:
            assert version == 'strange-version--0-'
    }

    @Issue("https://github.com/koral--/gradle-pitest-plugin/issues/166")
    void "wires unit test compilation when pitest is applied before the Android plugin"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject(true)
            project.extensions.extraProperties.set("android.newDsl", "true")
            project.evaluate()
        then:
            Task pitestDebug = project.tasks.findByName("${PitestPlugin.PITEST_TASK_NAME}Debug")
            assert pitestDebug != null
            assert pitestDebug.taskDependencies.getDependencies(pitestDebug)*.name.contains("compileDebugUnitTestSources")
    }

    void "use a separate classpath file for every variant to not break tasks running in parallel"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.evaluate()
        then:
            File debugFile = project.tasks.getByName('pitestDebug').additionalClasspathFile.get().asFile
            File releaseFile = project.tasks.getByName('pitestRelease').additionalClasspathFile.get().asFile
            debugFile != releaseFile
    }

    @SuppressWarnings("ImplicitClosureParameter")
    void "new variant API uses Android style variant directory names"() {
        when:
            Project project = AndroidUtils.createSampleApplicationProject()
            project.extensions.extraProperties.set("android.newDsl", "true")
            project.evaluate()
        then:
            Object classpath = project.tasks["${PitestPlugin.PITEST_TASK_NAME}FreeBlueRelease"].additionalClasspath.files
            assert classpath.find { it.toString().endsWith("sourceFolderJavaResources${File.separator}freeBlue${File.separator}release") }
            assert classpath.find { it.toString().endsWith("sourceFolderJavaResources${File.separator}test${File.separator}freeBlue${File.separator}release") }
    }

    @SuppressWarnings("ImplicitClosureParameter")
    void "resolves runtime classpath when pitest is applied before the Android plugin"() {
        when:
            Project project = AndroidUtils.createSampleApplicationProject(true)
            project.extensions.extraProperties.set("android.newDsl", "true")
            project.evaluate()
        then:
            Set<File> classpath = project.tasks["${PitestPlugin.PITEST_TASK_NAME}FreeBlueRelease"].additionalClasspath.files
            assert classpath.find { it.toString().endsWith('kotlin-reflect-1.6.10.jar') }
    }

    void "excludeMockableAndroidJar prevents the mockable jar task from being created"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.extensions.extraProperties.set("android.newDsl", "true")
            project.pitest.excludeMockableAndroidJar = true
            project.evaluate()
        then:
            assert project.tasks.findByName("${PitestPlugin.PITEST_TASK_NAME}Debug") != null
            assert project.tasks.findByName("pitestMockableAndroidJar") == null
    }

    void "variant tasks are added when pitest is applied before the Android plugin"() {
        when:
            Project project = AndroidUtils.createSampleApplicationProject(true)
            project.evaluate()
        then:
            assert project.tasks.findByName("${PitestPlugin.PITEST_TASK_NAME}FreeBlueRelease") != null
    }

    @Issue("https://github.com/koral--/gradle-pitest-plugin/issues/166")
    void "supports android newDsl flag and androidComponents"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.extensions.extraProperties.set("android.newDsl", "true")
            project.evaluate()
        then:
            project.plugins.hasPlugin(PitestPlugin)
            project.tasks.findByName("${PitestPlugin.PITEST_TASK_NAME}Debug") != null
    }

    void "supports new DSL compileSdk property when creating mockable android jar"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
            project.extensions.extraProperties.set("android.newDsl", "true")
            project.android.compileSdk = 31
            project.evaluate()
        then:
            Task mockableTask = project.tasks.findByName("pitestMockableAndroidJar")
            assert mockableTask != null
            assert mockableTask.outputJar.get().asFile.name == "pitest-android-31.jar"
    }

    void "reportDir defaults to the pitest directory in reports"() {
        when:
            Project project = AndroidUtils.createSampleLibraryProject()
        then:
            project.pitest.reportDir.get().asFile == new File(project.layout.buildDirectory.asFile.get(), "reports/pitest")
    }

    void "reportDir set by the user is not overwritten when another plugin is applied afterwards"() {
        given:
            Project project = AndroidUtils.createSampleLibraryProject()
            File customReportDir = new File(project.projectDir, "custom-report-dir")
            project.pitest.reportDir = customReportDir
        when:
            project.pluginManager.apply("jacoco")
        then:
            project.pitest.reportDir.get().asFile == customReportDir
    }

}
