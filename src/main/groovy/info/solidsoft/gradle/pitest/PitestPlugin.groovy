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
package info.solidsoft.gradle.pitest

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.TestPlugin
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.api.TestedVariant
import com.android.builder.model.AndroidProject
import groovy.transform.PackageScope
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.DefaultDomainObjectSet
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.BasePlugin
import org.gradle.util.VersionNumber

import static com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION

/**
 * The main class for Pitest plugin.
 */
class PitestPlugin implements Plugin<Project> {
    public final static String DEFAULT_PITEST_VERSION = '1.4.0'
    public final static String PITEST_TASK_GROUP = "Report"
    public final static String PITEST_TASK_NAME = "pitest"
    public final static String PITEST_CONFIGURATION_NAME = 'pitest'
    public final static PITEST_TEST_COMPILE_CONFIGURATION_NAME = 'pitestTestCompile'

    private final static List<String> DYNAMIC_LIBRARY_EXTENSIONS = ['so', 'dll', 'dylib']
    private final static List<String> DEFAULT_FILE_EXTENSIONS_TO_FILTER_FROM_CLASSPATH = ['pom'] + DYNAMIC_LIBRARY_EXTENSIONS

    private final static Logger log = Logging.getLogger(PitestPlugin)
    private final static VersionNumber ANDROID_GRADLE_PLUGIN_VERSION_NUMBER = VersionNumber.parse(ANDROID_GRADLE_PLUGIN_VERSION)

    @PackageScope
    //visible for testing
    final static String PIT_HISTORY_DEFAULT_FILE_NAME = 'pitHistory.txt'
    private final static String PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME = "pitClasspath"

    private Project project
    private PitestPluginExtension extension

    void apply(Project project) {
        this.project = project
        createConfigurations()

        extension = project.extensions.create("pitest", PitestPluginExtension)
        extension.pitestVersion = DEFAULT_PITEST_VERSION
        extension.fileExtensionsToFilter = DEFAULT_FILE_EXTENSIONS_TO_FILTER_FROM_CLASSPATH
        project.pluginManager.apply(BasePlugin)
        project.afterEvaluate {
            if (extension.mainSourceSets == null) {
                extension.mainSourceSets = project.android.sourceSets.main as Set<AndroidSourceSet>
            }
            if (extension.reportDir == null) {
                extension.reportDir = new File("${project.reporting.baseDir.path}/pitest")
            }

            project.plugins.withType(AppPlugin) { createPitestTasks(project.android.applicationVariants) }
            project.plugins.withType(LibraryPlugin) { createPitestTasks(project.android.libraryVariants) }
            project.plugins.withType(TestPlugin) { createPitestTasks(project.android.testVariants) }
            addPitDependencies(getMockableAndroidJarPath(project.android))
        }
    }

    private void createPitestTasks(DefaultDomainObjectSet<? extends BaseVariant> variants) {
        def globalTask = project.tasks.create(PITEST_TASK_NAME)
        globalTask.with {
            description = "Run PIT analysis for java classes, for all build variants"
            group = PITEST_TASK_GROUP
        }

        variants.all { BaseVariant variant ->
            PitestTask variantTask = project.tasks.create("${PITEST_TASK_NAME}${variant.name.capitalize()}", PitestTask)

            if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER < new VersionNumber(3, 2, 0, null)) {
                variantTask.dependsOn project.tasks.findByName("mockableAndroidJar")
            }
            configureTaskDefault(variantTask, variant)
            variantTask.with {
                description = "Run PIT analysis for java classes, for ${variant.name} build variant"
                group = PITEST_TASK_GROUP
            }
            variantTask.reportDir = new File(variantTask.reportDir, variant.name)
            variantTask.dependsOn "compile${variant.name.capitalize()}UnitTestSources"
            globalTask.dependsOn variantTask
        }
    }

    private void createConfigurations() {
        [PITEST_CONFIGURATION_NAME, PITEST_TEST_COMPILE_CONFIGURATION_NAME].each {
            project.rootProject.buildscript.configurations.maybeCreate(it).with {
                visible = false
                description = "The Pitest libraries to be used for this project."
            }
        }
    }

    private void configureTaskDefault(PitestTask task, BaseVariant variant) {
        FileCollection combinedTaskClasspath = project.files()

        combinedTaskClasspath.from(project.rootProject.buildscript.configurations[PITEST_TEST_COMPILE_CONFIGURATION_NAME])
        if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major >= 3) {
            combinedTaskClasspath.from(project.configurations["${variant.name}CompileClasspath"].copyRecursive {
                it.properties.dependencyProject == null
            })
            combinedTaskClasspath.from(project.configurations["${variant.name}UnitTestCompileClasspath"].copyRecursive {
                it.properties.dependencyProject == null
            })
        } else {
            combinedTaskClasspath.from(project.configurations["compile"])
            combinedTaskClasspath.from(project.configurations["testCompile"])
        }
        combinedTaskClasspath.from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/${variant.dirName}"))
        combinedTaskClasspath.from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/test/${variant.dirName}"))
        combinedTaskClasspath.from(project.files("${project.buildDir}/intermediates/unitTestConfig/test/${variant.dirName}"))
        if (variant instanceof TestedVariant) {
            combinedTaskClasspath.from(variant.unitTestVariant.javaCompiler.classpath)
            combinedTaskClasspath.from(project.files(variant.unitTestVariant.javaCompiler.destinationDir))
        }
        combinedTaskClasspath.from(variant.javaCompiler.classpath)
        combinedTaskClasspath.from(project.files(variant.javaCompiler.destinationDir))

        task.conventionMapping.with {
            additionalClasspath = {
                FileCollection filteredCombinedTaskClasspath = combinedTaskClasspath.filter { File file ->
                    !extension.fileExtensionsToFilter.find { file.name.endsWith(".$it") }
                }

                return filteredCombinedTaskClasspath
            }
            useAdditionalClasspathFile = { extension.useClasspathFile }
            additionalClasspathFile = { new File(project.buildDir, PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME) }
            launchClasspath = {
                project.rootProject.buildscript.configurations[PITEST_CONFIGURATION_NAME]
            }
            sourceDirs = {
                extension.mainSourceSets*.java.srcDirs.flatten() as Set
            }
            mutableCodePaths = {
                def additionalMutableCodePaths = extension.additionalMutableCodePaths ?: [] as Set
                additionalMutableCodePaths.add(variant.javaCompiler.destinationDir)
                def kotlinCompileTask = project.tasks.findByName("compile${variant.name.capitalize()}Kotlin")
                if (kotlinCompileTask != null) {
                    additionalMutableCodePaths.add(kotlinCompileTask.destinationDir)
                }
                additionalMutableCodePaths
            }

            testPlugin = { extension.testPlugin }
            reportDir = { extension.reportDir }
            targetClasses = {
                log.debug("Setting targetClasses. project.getGroup: {}, class: {}", project.getGroup(), project.getGroup()?.class)
                if (extension.targetClasses) {
                    return extension.targetClasses
                }
                if (project.getGroup()) {   //Assuming it is always a String class instance
                    return [project.getGroup() + ".*"] as Set
                }
                return null
            }
            targetTests = { extension.targetTests }
            dependencyDistance = { extension.dependencyDistance }
            threads = { extension.threads }
            mutateStaticInits = { extension.mutateStaticInits }
            includeJarFiles = { extension.includeJarFiles }
            mutators = { extension.mutators }
            excludedMethods = { extension.excludedMethods }
            excludedClasses = { extension.excludedClasses }
            excludedTestClasses = { extension.excludedTestClasses }
            avoidCallsTo = { extension.avoidCallsTo }
            verbose = { extension.verbose }
            timeoutFactor = { extension.timeoutFactor }
            timeoutConstInMillis = { extension.timeoutConstInMillis }
            maxMutationsPerClass = { extension.maxMutationsPerClass }
            childProcessJvmArgs = { extension.jvmArgs }
            outputFormats = { extension.outputFormats }
            failWhenNoMutations = { extension.failWhenNoMutations }
            includedGroups = { extension.includedGroups }
            excludedGroups = { extension.excludedGroups }
            detectInlinedCode = { extension.detectInlinedCode }
            timestampedReports = { extension.timestampedReports }
            historyInputLocation = { extension.historyInputLocation }
            historyOutputLocation = { extension.historyOutputLocation }
            enableDefaultIncrementalAnalysis = { extension.enableDefaultIncrementalAnalysis }
            defaultFileForHistoryData = { new File(project.buildDir, PIT_HISTORY_DEFAULT_FILE_NAME) }
            mutationThreshold = { extension.mutationThreshold }
            mutationEngine = { extension.mutationEngine }
            coverageThreshold = { extension.coverageThreshold }
            exportLineCoverage = { extension.exportLineCoverage }
            jvmPath = { extension.jvmPath }
            mainProcessJvmArgs = { extension.mainProcessJvmArgs }
            pluginConfiguration = { extension.pluginConfiguration }
            maxSurviving = { extension.maxSurviving }
            features = { extension.features }
        }
    }

    private void addPitDependencies(File mockableAndroidJar) {
        project.rootProject.buildscript.dependencies {
            log.info("Using PIT: $extension.pitestVersion")
            pitest "org.pitest:pitest-command-line:$extension.pitestVersion"
            pitestTestCompile project.files(mockableAndroidJar)
        }
    }

    private File getMockableAndroidJarPath(BaseExtension android) {
        def returnDefaultValues = android.testOptions.unitTests.returnDefaultValues

        String mockableAndroidJarFilename = "mockable-"
        mockableAndroidJarFilename += sanitizeSdkVersion(android.compileSdkVersion)
        if (returnDefaultValues) {
            mockableAndroidJarFilename += '.default-values'
        }

        File mockableJarDirectory = new File(project.rootProject.buildDir, AndroidProject.FD_GENERATED)
        if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major >= 3) {
            mockableAndroidJarFilename += '.v3'
            mockableJarDirectory = new File(project.buildDir, AndroidProject.FD_GENERATED)
        }
        mockableAndroidJarFilename += '.jar'

        return new File(mockableJarDirectory, mockableAndroidJarFilename)
    }

    static def sanitizeSdkVersion(def version) {
        return version.replaceAll('[^\\p{Alnum}.-]', '-')
    }
}
