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

import com.android.build.api.dsl.AndroidSourceSet
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.TestPlugin
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.api.TestedVariant
import com.android.builder.model.AndroidProject
import com.vdurmont.semver4j.Semver
import groovy.transform.CompileDynamic
import groovy.transform.PackageScope
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.DefaultDomainObjectSet
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.util.GradleVersion

import java.util.concurrent.Callable
import java.util.regex.Pattern

import static com.android.builder.model.Version.ANDROID_GRADLE_PLUGIN_VERSION
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP

/**
 * The main class for Pitest plugin.
 */
@CompileDynamic
class PitestPlugin implements Plugin<Project> {

    public final static String DEFAULT_PITEST_VERSION = '1.15.0'
    public final static String PITEST_TASK_GROUP = VERIFICATION_GROUP
    public final static String PITEST_TASK_NAME = "pitest"
    public final static String PITEST_REPORT_DIRECTORY_NAME = 'pitest'
    public final static String PITEST_CONFIGURATION_NAME = 'pitest'
    public final static String PITEST_TEST_COMPILE_CONFIGURATION_NAME = 'pitestTestCompile'

    private static final String PITEST_JUNIT5_PLUGIN_NAME = "junit5"
    private final static List<String> DYNAMIC_LIBRARY_EXTENSIONS = ['so', 'dll', 'dylib']
    private final static List<String> DEFAULT_FILE_EXTENSIONS_TO_FILTER_FROM_CLASSPATH = ['pom'] + DYNAMIC_LIBRARY_EXTENSIONS
    private final static Pattern MOCKABLE_ANDROID_JAR_PATH_PATTERN = Pattern.compile(".*${File.separator}platforms${File.separator}android-.*${File.separator}android.jar\$")

    @SuppressWarnings("FieldName")
    private final static Logger log = Logging.getLogger(PitestPlugin)
    private final static Semver ANDROID_GRADLE_PLUGIN_VERSION_NUMBER = new Semver(ANDROID_GRADLE_PLUGIN_VERSION)

    @PackageScope
    //visible for testing
    final static String PIT_HISTORY_DEFAULT_FILE_NAME = 'pitHistory.txt'
    private final static String PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME = "pitClasspath"
    public static final String PLUGIN_ID = 'pl.droidsonroids.pitest'

    private Project project
    private PitestPluginExtension pitestExtension

    static String sanitizeSdkVersion(String version) {
        return version.replaceAll('[^\\p{Alnum}.-]', '-')
    }

    static JavaCompile getJavaCompileTask(BaseVariant variant) {
        if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER >= new Semver("3.3.0")) {
            return variant.javaCompileProvider.get()
        } else {
            return variant.javaCompile
        }
    }

    void apply(Project project) {
        this.project = project
        failWithMeaningfulErrorMessageOnUnsupportedConfigurationInRootProjectBuildScript()
        createConfigurations()

        pitestExtension = project.extensions.create("pitest", PitestPluginExtension, project)
        pitestExtension.pitestVersion.set(DEFAULT_PITEST_VERSION)
        pitestExtension.fileExtensionsToFilter.set(DEFAULT_FILE_EXTENSIONS_TO_FILTER_FROM_CLASSPATH)
        pitestExtension.useClasspathFile.set(false)
        pitestExtension.verbosity.set("NO_SPINNER")
        pitestExtension.addJUnitPlatformLauncher.set(true)

        project.pluginManager.apply(BasePlugin)

        project.plugins.whenPluginAdded {
            ReportingExtension reportingExtension = project.extensions.findByType(ReportingExtension)
            if (reportingExtension != null) {
                pitestExtension.reportDir.set(new File(reportingExtension.baseDir, "pitest"))
            }
        }

        project.afterEvaluate {
            if (pitestExtension.mainSourceSets.empty()) {
                pitestExtension.mainSourceSets.set(project.android.sourceSets.main as Set<AndroidSourceSet>)
            }
            if (pitestExtension.testSourceSets.empty()) {
                pitestExtension.testSourceSets.set(project.android.sourceSets.test as Set<AndroidSourceSet>)
            }

            project.plugins.withType(AppPlugin) { createPitestTasks(project.android.applicationVariants) }
            project.plugins.withType(LibraryPlugin) { createPitestTasks(project.android.libraryVariants) }
            project.plugins.withType(TestPlugin) { createPitestTasks(project.android.testVariants) }
            addPitDependencies()
        }
        addJUnitPlatformLauncherDependencyIfNeeded()
    }

    private void failWithMeaningfulErrorMessageOnUnsupportedConfigurationInRootProjectBuildScript() {
        if (project.rootProject.buildscript.configurations.findByName(PITEST_CONFIGURATION_NAME) != null) {
            throw new GradleException("The '${PITEST_CONFIGURATION_NAME}' buildscript configuration found in the root project. " +
                    "This is no longer supported in 1.5.0+ and has to be changed to the regular (sub)project configuration. " +
                    "See the project FAQ for migration details.")
        }
    }

    @SuppressWarnings("BuilderMethodWithSideEffects")
    private void createPitestTasks(DefaultDomainObjectSet<? extends BaseVariant> variants) {
        Task globalTask = project.tasks.create(PITEST_TASK_NAME)
        globalTask.with {
            description = "Run PIT analysis for java classes, for all build variants"
            group = PITEST_TASK_GROUP
            shouldRunAfter("test")
        }

        variants.all { BaseVariant variant ->
            PitestTask variantTask = project.tasks.create("${PITEST_TASK_NAME}${variant.name.capitalize()}", PitestTask)

            boolean includeMockableAndroidJar = !pitestExtension.excludeMockableAndroidJar.getOrElse(false)
            if (includeMockableAndroidJar) {
                addMockableAndroidJarDependencies()
            }

            Task mockableAndroidJarTask
            if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER < new Semver("3.2.0")) {
                mockableAndroidJarTask = project.tasks.findByName("mockableAndroidJar")
                configureTaskDefault(variantTask, variant, getMockableAndroidJar(project.android))
            } else {
                mockableAndroidJarTask = project.tasks.maybeCreate("pitestMockableAndroidJar", PitestMockableAndroidJarTask)
                configureTaskDefault(variantTask, variant, mockableAndroidJarTask.outputJar)
            }

            if (includeMockableAndroidJar) {
                variantTask.dependsOn mockableAndroidJarTask
            }

            variantTask.with {
                description = "Run PIT analysis for java classes, for ${variant.name} build variant"
                group = PITEST_TASK_GROUP
                shouldRunAfter("test${variant.name.capitalize()}UnitTest")
            }
            suppressPassingDeprecatedTestPluginForNewerPitVersions(variantTask)

            variantTask.dependsOn "compile${variant.name.capitalize()}UnitTestSources"
            globalTask.dependsOn variantTask
        }
    }

    private void addMockableAndroidJarDependencies() {
        //according to https://search.maven.org/artifact/com.google.android/android/4.1.1.4/jar
        project.buildscript.dependencies {
            "$PITEST_TEST_COMPILE_CONFIGURATION_NAME" "org.json:json:20080701"
            "$PITEST_TEST_COMPILE_CONFIGURATION_NAME" "xpp3:xpp3:1.1.4c"
            "$PITEST_TEST_COMPILE_CONFIGURATION_NAME" "xerces:xmlParserAPIs:2.6.2"
            "$PITEST_TEST_COMPILE_CONFIGURATION_NAME" "org.khronos:opengl-api:gl1.1-android-2.1_r1"
            "$PITEST_TEST_COMPILE_CONFIGURATION_NAME" "org.apache.httpcomponents:httpclient:4.0.1"
            "$PITEST_TEST_COMPILE_CONFIGURATION_NAME" "commons-logging:commons-logging:1.1.1"
        }
    }

    @SuppressWarnings("BuilderMethodWithSideEffects")
    private void createConfigurations() {
        [PITEST_CONFIGURATION_NAME, PITEST_TEST_COMPILE_CONFIGURATION_NAME].each { configuration ->
            project.buildscript.configurations.maybeCreate(configuration).with {
                visible = false
                description = "The PIT libraries to be used for this project."
            }
        }
        project.configurations {
            pitestRuntimeOnly.extendsFrom testRuntimeOnly
        }
    }

    @SuppressWarnings(["Instanceof", "UnnecessarySetter", "DuplicateNumberLiteral"])
    private void configureTaskDefault(PitestTask task, BaseVariant variant, File mockableAndroidJar) {
        FileCollection combinedTaskClasspath = project.files()

        combinedTaskClasspath.with {
            from(project.buildscript.configurations[PITEST_TEST_COMPILE_CONFIGURATION_NAME])
            if (!pitestExtension.excludeMockableAndroidJar.getOrElse(false)) {
                from(mockableAndroidJar)
            }

            if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major == 3 && project.findProperty("android.enableJetifier") != "true") {
                if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.minor < 3) {
                    from(project.configurations["${variant.name}CompileClasspath"].copyRecursive { dependency ->
                        dependency.properties.dependencyProject == null
                    })
                    from(project.configurations["${variant.name}UnitTestCompileClasspath"].copyRecursive { dependency ->
                        dependency.properties.dependencyProject == null
                    })
                } else if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.minor < 4) {
                    from(project.configurations["${variant.name}CompileClasspath"])
                    from(project.configurations["${variant.name}UnitTestCompileClasspath"])
                }
            } else if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major == 4) {
                from(project.configurations["compile"])
                from(project.configurations["testCompile"])
            } else if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major > 4 && project.findProperty("android.enableJetifier") != "true") {
                Configuration unittestRuntimeConfig = project.configurations.getByName("${variant.name}UnitTestRuntimeClasspath")
                from(unittestRuntimeConfig.copyRecursive { dependency ->
                    dependency.properties.dependencyProject == null && dependency.version != null
                }.shouldResolveConsistentlyWith(unittestRuntimeConfig))
            }
            from(project.configurations["pitestRuntimeOnly"])
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/${variant.dirName}"))
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/test/${variant.dirName}"))
            from(project.files("${project.buildDir}/intermediates/java_res/${variant.dirName}/out"))
            from(project.files("${project.buildDir}/intermediates/java_res/${variant.dirName}UnitTest/out"))
            from(project.files("${project.buildDir}/intermediates/unitTestConfig/test/${variant.dirName}"))
            if (variant instanceof TestedVariant) {
                variant.unitTestVariant?.with { unitTestVariant ->
                    from(getJavaCompileTask(unitTestVariant).classpath)
                    from(project.files(getJavaCompileTask(unitTestVariant).destinationDirectory.asFile))
                }
            }
            from(getJavaCompileTask(variant).classpath)
            from(project.files(getJavaCompileTask(variant).destinationDirectory.asFile))
        }

        task.with {
            defaultFileForHistoryData.set(new File(project.buildDir, PIT_HISTORY_DEFAULT_FILE_NAME))
            testPlugin.set(pitestExtension.testPlugin)
            reportDir.set(pitestExtension.reportDir.dir(variant.name))
            targetClasses.set(project.providers.provider {
                log.debug("Setting targetClasses. project.getGroup: {}, class: {}", project.getGroup(), project.getGroup()?.class)
                if (pitestExtension.targetClasses.isPresent()) {
                    return pitestExtension.targetClasses.get()
                }
                if (project.getGroup()) {   //Assuming it is always a String class instance
                    return [project.getGroup() + ".*"] as Set
                }
                return null
            } as Provider<Iterable<String>>)
            targetTests.set(project.providers.provider {
                //unless explicitly configured use targetClasses - https://github.com/szpak/gradle-pitest-plugin/issues/144
                if (pitestExtension.targetTests.isPresent()) {
                    //getOrElseGet() is not available - https://github.com/gradle/gradle/issues/10520
                    return pitestExtension.targetTests.get()
                } else {
                    return targetClasses.getOrNull()
                }
            } as Provider<Iterable<String>>)
            threads.set(pitestExtension.threads)
            mutators.set(pitestExtension.mutators)
            excludedMethods.set(pitestExtension.excludedMethods)
            excludedClasses.set(pitestExtension.excludedClasses)
            excludedTestClasses.set(pitestExtension.excludedTestClasses)
            avoidCallsTo.set(pitestExtension.avoidCallsTo)
            verbose.set(pitestExtension.verbose)
            verbosity.set(pitestExtension.verbosity)
            timeoutFactor.set(pitestExtension.timeoutFactor)
            timeoutConstInMillis.set(pitestExtension.timeoutConstInMillis)
            childProcessJvmArgs.set(pitestExtension.jvmArgs)
            outputFormats.set(pitestExtension.outputFormats)
            failWhenNoMutations.set(pitestExtension.failWhenNoMutations)
            skipFailingTests.set(pitestExtension.skipFailingTests)
            includedGroups.set(pitestExtension.includedGroups)
            excludedGroups.set(pitestExtension.excludedGroups)
            fullMutationMatrix.set(pitestExtension.fullMutationMatrix)
            includedTestMethods.set(pitestExtension.includedTestMethods)
            Set javaSourceSet = pitestExtension.mainSourceSets.get()*.java.srcDirs.flatten() as Set
            Set resourcesSourceSet = pitestExtension.mainSourceSets.get()*.resources.srcDirs.flatten() as Set
            sourceDirs.setFrom(javaSourceSet + resourcesSourceSet)
            detectInlinedCode.set(pitestExtension.detectInlinedCode)
            timestampedReports.set(pitestExtension.timestampedReports)
            additionalClasspath.setFrom({
                FileCollection filteredCombinedTaskClasspath = combinedTaskClasspath.filter { File file ->
                    if (pitestExtension.excludeMockableAndroidJar.getOrElse(false) && MOCKABLE_ANDROID_JAR_PATH_PATTERN.matcher(file.absolutePath).matches()) {
                        return false
                    } else {
                        return !pitestExtension.fileExtensionsToFilter.getOrElse([]).find { extension -> file.name.endsWith(".$extension") }
                    }
                }

                return filteredCombinedTaskClasspath
            } as Callable<FileCollection>, pitestExtension.testSourceSets.get()*.java.srcDirs.flatten(), pitestExtension.testSourceSets.get()*.resources.srcDirs.flatten())
            useAdditionalClasspathFile.set(pitestExtension.useClasspathFile)
            additionalClasspathFile.set(new File(project.buildDir, PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME))
            mutableCodePaths.setFrom({
                Object additionalMutableCodePaths = pitestExtension.additionalMutableCodePaths ?: [] as Set
                additionalMutableCodePaths.add(getJavaCompileTask(variant).destinationDirectory.asFile)
                Task kotlinCompileTask = project.tasks.findByName("compile${variant.name.capitalize()}Kotlin")
                if (kotlinCompileTask != null) {
                    additionalMutableCodePaths.add(kotlinCompileTask.destinationDirectory.asFile)
                }
                additionalMutableCodePaths
            } as Callable<Set<File>>)
            historyInputLocation.set(pitestExtension.historyInputLocation)
            historyOutputLocation.set(pitestExtension.historyOutputLocation)
            enableDefaultIncrementalAnalysis.set(pitestExtension.enableDefaultIncrementalAnalysis)
            mutationThreshold.set(pitestExtension.mutationThreshold)
            coverageThreshold.set(pitestExtension.coverageThreshold)
            testStrengthThreshold.set(pitestExtension.testStrengthThreshold)
            mutationEngine.set(pitestExtension.mutationEngine)
            exportLineCoverage.set(pitestExtension.exportLineCoverage)
            jvmPath.set(pitestExtension.jvmPath)
            mainProcessJvmArgs.set(pitestExtension.mainProcessJvmArgs)
            launchClasspath.setFrom({
                project.buildscript.configurations[PITEST_CONFIGURATION_NAME]
            } as Callable<Configuration>)
            pluginConfiguration.set(pitestExtension.pluginConfiguration)
            maxSurviving.set(pitestExtension.maxSurviving)
            useClasspathJar.set(pitestExtension.useClasspathJar)
            features.set(pitestExtension.features)
            inputEncoding.set(pitestExtension.inputCharset)
            outputEncoding.set(pitestExtension.outputCharset)
        }
    }

    private void addPitDependencies() {
        project.buildscript.dependencies {
            String pitestVersion = pitestExtension.pitestVersion.get()
            log.info("Using PIT: $pitestVersion")
            pitest "org.pitest:pitest-command-line:$pitestVersion"
            if (pitestExtension.junit5PluginVersion.isPresent()) {
                if (pitestExtension.testPlugin.isPresent() && pitestExtension.testPlugin.get() != PITEST_JUNIT5_PLUGIN_NAME) {
                    log.warn("Specified 'junit5PluginVersion', but other plugin is configured in 'testPlugin' for PIT: '${pitestExtension.testPlugin.get()}'")
                }

                String junit5PluginDependencyAsString = "org.pitest:pitest-junit5-plugin:${pitestExtension.junit5PluginVersion.get()}"
                log.info("Adding dependency: ${junit5PluginDependencyAsString}")
                pitest project.dependencies.create(junit5PluginDependencyAsString)
            }
        }
    }

    @SuppressWarnings("DuplicateNumberLiteral")
    private File getMockableAndroidJar(BaseExtension android) {
        boolean returnDefaultValues = android.testOptions.unitTests.returnDefaultValues

        String mockableAndroidJarFilename = "mockable-"
        mockableAndroidJarFilename += sanitizeSdkVersion(android.compileSdkVersion)
        if (returnDefaultValues) {
            mockableAndroidJarFilename += '.default-values'
        }

        File mockableJarDirectory
        if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major >= 3) {
            mockableAndroidJarFilename += '.v3'
            mockableJarDirectory = new File(project.buildDir, AndroidProject.FD_GENERATED)
        } else {
            mockableJarDirectory = new File(project.rootProject.buildDir, AndroidProject.FD_GENERATED)
        }
        mockableAndroidJarFilename += '.jar'

        return new File(mockableJarDirectory, mockableAndroidJarFilename)
    }

    private void suppressPassingDeprecatedTestPluginForNewerPitVersions(PitestTask pitestTask) {
        if (pitestExtension.testPlugin.isPresent()) {
            log.warn("DEPRECATION WARNING. `testPlugin` is deprecated starting with GPP 1.7.4. It is also not used starting with PIT 1.6.7 (to be removed in 1.8.0).")
            String configuredPitVersion = pitestExtension.pitestVersion.get()
            try {
                final GradleVersion minimalPitVersionNotNeedingTestPluginProperty = GradleVersion.version("1.6.7")
                if (GradleVersion.version(configuredPitVersion) >= minimalPitVersionNotNeedingTestPluginProperty) {
                    log.info("Passing '--testPlugin' to PIT disabled for PIT 1.6.7+. See https://github.com/szpak/gradle-pitest-plugin/issues/277")
                    pitestTask.testPlugin.set((String) null)
                }
            } catch (IllegalArgumentException e) {
                log.warn("Error during PIT versions comparison. Is '$configuredPitVersion' really valid? If yes, please report that case. " +
                        "Assuming PIT version is newer than 1.6.7.")
                log.warn("Original exception: ${e.class.name}:${e.message}")
            }
        }
    }

    private void addJUnitPlatformLauncherDependencyIfNeeded() {
        project.configurations.maybeCreate("testImplementation")
        project.configurations.named("testImplementation").configure { testImplementation ->
            testImplementation.withDependencies { directDependencies ->
                if (!pitestExtension.addJUnitPlatformLauncher.isPresent() || !pitestExtension.addJUnitPlatformLauncher.get()) {
                    log.info("'addJUnitPlatformLauncher' feature explicitly disabled in configuration. " +
                            "Add junit-platform-launcher manually or expect 'Minion exited abnormally due to UNKNOWN_ERROR' or 'NoClassDefFoundError'")
                    return
                }

                //Note: For simplicity, adding also for older pitest-junit5-plugin versions (<1.2.0), which is not needed

                final String orgJUnitPlatformGroup = "org.junit.platform"

                log.debug("Direct ${testImplementation.name} dependencies (${directDependencies.size()}): ${directDependencies}")

                //copy() seems to copy also something that refers to original configuration and generates StackOverflow on getting components
                Configuration tmpTestImplementation = project.configurations.maybeCreate("tmpTestImplementation")
                directDependencies.each { directDependency ->
                    tmpTestImplementation.dependencies.add(directDependency)
                }

                ResolutionResult resolutionResult = tmpTestImplementation.incoming.resolutionResult
                Set<ResolvedComponentResult> allResolvedComponents = resolutionResult.allComponents
                log.debug("All resolved components ${testImplementation.name} (${allResolvedComponents.size()}): ${allResolvedComponents}")

                ResolvedComponentResult foundJunitPlatformComponent = allResolvedComponents.find { ResolvedComponentResult componentResult ->
                    ModuleVersionIdentifier moduleVersion = componentResult.moduleVersion
                    return moduleVersion.group == orgJUnitPlatformGroup &&
                            (moduleVersion.name == "junit-platform-engine" || moduleVersion.name == "junit-platform-commons")
                }

                if (!foundJunitPlatformComponent) {
                    log.info("No ${orgJUnitPlatformGroup} components founds in ${testImplementation.name}, junit-platform-launcher will not be added")
                    return
                }

                String junitPlatformLauncherDependencyAsString = "${orgJUnitPlatformGroup}:junit-platform-launcher:${foundJunitPlatformComponent.moduleVersion.version}"
                log.info("${orgJUnitPlatformGroup} component (${foundJunitPlatformComponent}) found in ${testImplementation.name}, " +
                        "adding junit-platform-launcher (${junitPlatformLauncherDependencyAsString}) to testRuntimeOnly")
                project.configurations.named("testRuntimeOnly").configure({ Configuration testRuntimeOnly ->
                    testRuntimeOnly.dependencies.add(project.dependencies.create(junitPlatformLauncherDependencyAsString))
                } as Action<Configuration>)
            }
        }
    }

}
