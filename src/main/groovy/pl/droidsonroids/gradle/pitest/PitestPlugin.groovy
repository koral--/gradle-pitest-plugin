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
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasUnitTest
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.DynamicFeaturePlugin
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.TestPlugin
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
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.util.GradleVersion

import java.util.concurrent.Callable

import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP

/**
 * The main class for Pitest plugin.
 */
@CompileDynamic
class PitestPlugin implements Plugin<Project> {

    public final static String DEFAULT_PITEST_VERSION = '1.19.5'
    public final static String PITEST_TASK_GROUP = VERIFICATION_GROUP
    public final static String PITEST_TASK_NAME = "pitest"
    public final static String PITEST_REPORT_DIRECTORY_NAME = 'pitest'
    public final static String PITEST_CONFIGURATION_NAME = 'pitest'
    public final static String PITEST_TEST_COMPILE_CONFIGURATION_NAME = 'pitestTestCompile'

    private final static int AGP_9_MAJOR_VERSION = 9
    private static final String PITEST_JUNIT5_PLUGIN_NAME = "junit5"
    private final static List<String> DYNAMIC_LIBRARY_EXTENSIONS = ['so', 'dll', 'dylib']
    private final static List<String> DEFAULT_FILE_EXTENSIONS_TO_FILTER_FROM_CLASSPATH = ['pom'] + DYNAMIC_LIBRARY_EXTENSIONS

    @SuppressWarnings("FieldName")
    private final static Logger log = Logging.getLogger(PitestPlugin)
    private final static Semver ANDROID_GRADLE_PLUGIN_VERSION_NUMBER = resolveAgpVersion()

    private static Semver resolveAgpVersion() {
        try {
            Class<?> clazz = PitestPlugin.classLoader.loadClass("com.android.Version")
            return new Semver(clazz.getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null) as String)
        } catch (ReflectiveOperationException ignored) {
            try {
                Class<?> clazz = PitestPlugin.classLoader.loadClass("com.android.builder.model.Version")
                return new Semver(clazz.getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null) as String)
            } catch (ReflectiveOperationException ignored2) {
                return new Semver("0.0.0")
            }
        }
    }

    @PackageScope
    //visible for testing
    final static String PIT_HISTORY_DEFAULT_FILE_NAME = 'pitHistory.txt'
    final static String PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME = "pitClasspath"
    public static final String PLUGIN_ID = 'pl.droidsonroids.pitest'

    private Project project
    private PitestPluginExtension pitestExtension
    private Task globalPitestTask
    private boolean newApiVariantTasksEnabled

    static String sanitizeSdkVersion(String version) {
        return version.replaceAll('[^\\p{Alnum}.-]', '-')
    }

    static JavaCompile getJavaCompileTask(Project project, String variantName) {
        return project.tasks.findByName("compile${variantName.capitalize()}JavaWithJavac") as JavaCompile
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
                pitestExtension.reportDir.set(new File(reportingExtension.baseDirectory.get().asFile, "pitest"))
            }
        }

        List<Map<String, Object>> collectedVariantInfos = []

        Action<Plugin> registerVariantCallbacks = {
            AndroidComponentsExtension androidComponents = project.extensions.findByType(AndroidComponentsExtension)
            if (androidComponents != null) {
                androidComponents.onVariants(androidComponents.selector().all()) { variant ->
                    String flavorName = variant.flavorName ?: ''
                    String dirName = [flavorName, variant.buildType].findAll().join('/')

                    String unitTestName = null
                    try {
                        Object unitTest = variant.unitTest
                        if (unitTest != null) {
                            unitTestName = unitTest.name
                        }
                    } catch (ignored) {
                        // No unit test support for this variant
                    }

                    Map<String, Object> variantInfo = [
                            name: variant.name,
                            buildType: variant.buildType ?: '',
                            flavorName: flavorName,
                            dirName: dirName,
                            unitTestName: unitTestName,
                            supportsUnitTests: HasUnitTest.isInstance(variant),
                    ]
                    collectedVariantInfos.add(variantInfo)
                    if (newApiVariantTasksEnabled) {
                        createPitestTaskForVariantInfo(variantInfo)
                    }
                }
            }
        }
        ["com.android.application", "com.android.library", "com.android.dynamic-feature", "com.android.test"].each { String pluginId ->
            project.plugins.withId(pluginId, registerVariantCallbacks)
        }

        project.afterEvaluate {
            //has to run after the Android plugin created the test configurations, which is not guaranteed when this
            //plugin is applied before `com.android.*`, but still before any of them gets copied for the Pitest classpath
            addJUnitPlatformLauncherDependencyIfNeeded()

            Object androidSourceSets = project.extensions.findByName("android")?.sourceSets
            if (pitestExtension.mainSourceSets.empty() && androidSourceSets?.findByName("main") != null) {
                pitestExtension.mainSourceSets.set(androidSourceSets.main as Set<AndroidSourceSet>)
            }
            if (pitestExtension.testSourceSets.empty() && androidSourceSets?.findByName("test") != null) {
                pitestExtension.testSourceSets.set(androidSourceSets.test as Set<AndroidSourceSet>)
            }

            boolean useNewVariantApi = ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major >= AGP_9_MAJOR_VERSION ||
                    project.findProperty("android.newDsl") == "true" ||
                    !hasLegacyVariants(project)

            if (useNewVariantApi) {
                // variants collected so far get tasks now; later ones are handled by the onVariants callback
                newApiVariantTasksEnabled = true
                createGlobalPitestTask()
                collectedVariantInfos.each { Map<String, Object> variantInfo -> createPitestTaskForVariantInfo(variantInfo) }
            } else {
                project.plugins.withType(AppPlugin) { createPitestTasksLegacy(project.android.applicationVariants) }
                project.plugins.withType(LibraryPlugin) { createPitestTasksLegacy(project.android.libraryVariants) }
                project.plugins.withType(DynamicFeaturePlugin) { createPitestTasksLegacy(project.android.applicationVariants) }
                project.plugins.withType(TestPlugin) { createPitestTasksLegacy(project.android.testVariants) }
            }
            addPitDependencies()
        }
    }

    private void failWithMeaningfulErrorMessageOnUnsupportedConfigurationInRootProjectBuildScript() {
        if (project.rootProject.buildscript.configurations.findByName(PITEST_CONFIGURATION_NAME) != null) {
            throw new GradleException("The '${PITEST_CONFIGURATION_NAME}' buildscript configuration found in the root project. " +
                    "This is no longer supported in 1.5.0+ and has to be changed to the regular (sub)project configuration. " +
                    "See the project FAQ for migration details.")
        }
    }

    @SuppressWarnings("BuilderMethodWithSideEffects")
    private void createGlobalPitestTask() {
        globalPitestTask = project.tasks.create(PITEST_TASK_NAME)
        globalPitestTask.with {
            description = "Run PIT analysis for java classes, for all build variants"
            group = PITEST_TASK_GROUP
            shouldRunAfter("test")
        }
    }

    @SuppressWarnings("BuilderMethodWithSideEffects")
    private void createPitestTaskForVariantInfo(Map<String, Object> variantInfo) {
        Task globalTask = globalPitestTask
        String variantName = variantInfo.name as String
        String variantFlavorName = variantInfo.flavorName as String
        String variantDirName = variantInfo.dirName as String
        String unitTestName = variantInfo.unitTestName as String
        boolean supportsUnitTests = variantInfo.supportsUnitTests as boolean

        if (isBaselineProfileVariantByName(variantName, variantFlavorName)) {
            return
        }

        //variants which do not support unit tests at all (e.g. in `com.android.test` modules) still get a Pitest task
        if (supportsUnitTests && unitTestName == null) {
            log.info("Skipping Pitest task creation for variant '${variantName}' because unit tests are not enabled for it.")
            return
        }

        PitestTask variantTask = project.tasks.create("${PITEST_TASK_NAME}${variantName.capitalize()}", PitestTask)

        boolean includeMockableAndroidJar = !pitestExtension.excludeMockableAndroidJar.getOrElse(false)
        if (includeMockableAndroidJar) {
            addMockableAndroidJarDependencies()
        }

        Task mockableAndroidJarTask = project.tasks.maybeCreate("pitestMockableAndroidJar", PitestMockableAndroidJarTask)
        configureTaskDefaultByName(variantTask, variantName, variantDirName, unitTestName, mockableAndroidJarTask.outputJar)

        if (includeMockableAndroidJar) {
            variantTask.dependsOn mockableAndroidJarTask
        }

        variantTask.with {
            description = "Run PIT analysis for java classes, for ${variantName} build variant"
            group = PITEST_TASK_GROUP
            shouldRunAfter("test${variantName.capitalize()}UnitTest")
        }
        suppressPassingDeprecatedTestPluginForNewerPitVersions(variantTask)

        //resolved lazily, the tasks may not exist yet when this plugin is applied before `com.android.*`
        variantTask.dependsOn { project.tasks.findByName("compile${variantName.capitalize()}UnitTestSources") ?: [] }
        variantTask.mustRunAfter { project.tasks.findByName("compileDebugJavaWithJavac") ?: [] }
        globalTask.dependsOn variantTask
    }

    private static boolean hasLegacyVariants(Project project) {
        if (!project.hasProperty("android")) {
            return false
        }
        Object androidExt = project.extensions.findByName("android")
        if (androidExt == null) {
            return false
        }
        return androidExt.hasProperty("applicationVariants") ||
                androidExt.hasProperty("libraryVariants") ||
                androidExt.hasProperty("testVariants")
    }

    @SuppressWarnings("BuilderMethodWithSideEffects")
    private void createPitestTasksLegacy(Object variants) {
        Task globalTask = project.tasks.create(PITEST_TASK_NAME)
        globalTask.with {
            description = "Run PIT analysis for java classes, for all build variants"
            group = PITEST_TASK_GROUP
            shouldRunAfter("test")
        }

        variants.all { variant ->
            if (variant.hasProperty("unitTestVariant") && variant.unitTestVariant == null) {
                log.info("Skipping Pitest task creation for variant '${variant.name}' because unit tests are not enabled for it.")
                return
            }
            PitestTask variantTask = project.tasks.create("${PITEST_TASK_NAME}${variant.name.capitalize()}", PitestTask)

            boolean includeMockableAndroidJar = !pitestExtension.excludeMockableAndroidJar.getOrElse(false)
            if (includeMockableAndroidJar) {
                addMockableAndroidJarDependencies()
            }

            Task mockableAndroidJarTask
            if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER < new Semver("3.2.0")) {
                mockableAndroidJarTask = project.tasks.findByName("mockableAndroidJar")
                configureTaskDefaultLegacy(variantTask, variant, getMockableAndroidJar(project.android))
            } else {
                mockableAndroidJarTask = project.tasks.maybeCreate("pitestMockableAndroidJar", PitestMockableAndroidJarTask)
                configureTaskDefaultLegacy(variantTask, variant, mockableAndroidJarTask.outputJar)
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
            Task debugJavaCompileTask = project.tasks.findByName("compileDebugJavaWithJavac")
            if (debugJavaCompileTask != null) {
                variantTask.mustRunAfter(debugJavaCompileTask)
            }
            globalTask.dependsOn variantTask
        }
    }

    private void addMockableAndroidJarDependencies() {
        //according to https://search.maven.org/artifact/com.google.android/android/4.1.1.4/jar
        project.dependencies {
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
            project.configurations.maybeCreate(configuration).with {
                visible = false
                description = "The PIT libraries to be used for this project."
            }
        }
        project.configurations {
            pitestRuntimeOnly.extendsFrom testRuntimeOnly
        }
    }

    @SuppressWarnings(["Instanceof", "UnnecessarySetter", "DuplicateNumberLiteral"])
    private void configureTaskDefaultByName(PitestTask task, String variantName, String dirName, String unitTestName, File mockableAndroidJar) {
        FileCollection combinedTaskClasspath = project.files()

        combinedTaskClasspath.with {
            from(project.configurations[PITEST_TEST_COMPILE_CONFIGURATION_NAME])
            if (!pitestExtension.excludeMockableAndroidJar.getOrElse(false)) {
                from(mockableAndroidJar)
            }

            if (project.findProperty("android.enableJetifier") != "true") {
                Configuration runtimeConfig = project.configurations.findByName("${variantName}RuntimeClasspath")
                if (runtimeConfig != null) {
                    Configuration copiedRuntimeConfig = runtimeConfig.copyRecursive { dependency ->
                        dependency.properties.dependencyProject == null && dependency.version != null
                    }.shouldResolveConsistentlyWith(runtimeConfig)

                    from(copiedRuntimeConfig.incoming.artifactView { view ->
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)
                }

                Configuration unittestRuntimeConfig = project.configurations.findByName("${variantName}UnitTestRuntimeClasspath")
                if (unittestRuntimeConfig != null) {
                    Configuration copiedUnittestRuntimeConfig = unittestRuntimeConfig.copyRecursive { dependency ->
                        dependency.properties.dependencyProject == null && dependency.version != null
                    }.shouldResolveConsistentlyWith(unittestRuntimeConfig)

                    from(copiedUnittestRuntimeConfig.incoming.artifactView { view ->
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)
                } else {
                    log.info("Configuration '${variantName}UnitTestRuntimeClasspath' not found (variant may not have unit tests enabled)")
                }
            }
            from(project.configurations["pitestRuntimeOnly"])
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/${dirName}"))
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/test/${dirName}"))
            from(project.files("${project.buildDir}/intermediates/java_res/${dirName}/out"))
            from(project.files("${project.buildDir}/intermediates/java_res/${dirName}UnitTest/out"))
            from(project.files("${project.buildDir}/intermediates/unitTestConfig/test/${dirName}"))
            from { project.tasks.findByName("compile${variantName.capitalize()}Kotlin")?.destinationDirectory?.asFile }

            if (unitTestName != null) {
                from { project.tasks.findByName("compile${unitTestName.capitalize()}Kotlin")?.destinationDirectory?.asFile }
                from(getJavaCompileClasspathProviderByName(unitTestName))
                from(getJavaCompileDestinationProviderByName(unitTestName))
            }
            from(getJavaCompileClasspathProviderByName(variantName))
            from(getJavaCompileDestinationProviderByName(variantName))
        }

        configureCommonTaskProperties(task, variantName, combinedTaskClasspath)
    }

    @SuppressWarnings(["Instanceof", "UnnecessarySetter", "DuplicateNumberLiteral"])
    private void configureTaskDefaultLegacy(PitestTask task, Object variant, File mockableAndroidJar) {
        if (isBaselineProfileVariantLegacy(variant)) {
            return
        }
        FileCollection combinedTaskClasspath = project.files()

        combinedTaskClasspath.with {
            from(project.configurations[PITEST_TEST_COMPILE_CONFIGURATION_NAME])
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
                Configuration runtimeConfig = project.configurations.findByName("${variant.name}RuntimeClasspath")
                if (runtimeConfig != null) {
                    Configuration copiedRuntimeConfig = runtimeConfig.copyRecursive { dependency ->
                        dependency.properties.dependencyProject == null && dependency.version != null
                    }.shouldResolveConsistentlyWith(runtimeConfig)

                    from(copiedRuntimeConfig.incoming.artifactView { view ->
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)
                }

                Configuration unittestRuntimeConfig = project.configurations.findByName("${variant.name}UnitTestRuntimeClasspath")
                if (unittestRuntimeConfig != null) {
                    Configuration copiedUnittestRuntimeConfig = unittestRuntimeConfig.copyRecursive { dependency ->
                        dependency.properties.dependencyProject == null && dependency.version != null
                    }.shouldResolveConsistentlyWith(unittestRuntimeConfig)

                    from(copiedUnittestRuntimeConfig.incoming.artifactView { view ->
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)
                }
            }
            from(project.configurations["pitestRuntimeOnly"])
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/${variant.dirName}"))
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/test/${variant.dirName}"))
            from(project.files("${project.buildDir}/intermediates/java_res/${variant.dirName}/out"))
            from(project.files("${project.buildDir}/intermediates/java_res/${variant.dirName}UnitTest/out"))
            from(project.files("${project.buildDir}/intermediates/unitTestConfig/test/${variant.dirName}"))
            Task kotlinCompileTask = project.tasks.findByName("compile${variant.name.capitalize()}Kotlin")
            if (kotlinCompileTask != null) {
                from(kotlinCompileTask.destinationDirectory.asFile)
            }

            Object unitTestVariant = null
            try {
                unitTestVariant = variant.unitTestVariant
            } catch (MissingPropertyException ignored) {
                // variant may not support unit tests
            }
            if (unitTestVariant != null) {
                Task testKotlinCompileTask = project.tasks.findByName("compile${unitTestVariant.name.capitalize()}Kotlin")
                if (testKotlinCompileTask != null) {
                    from(testKotlinCompileTask.destinationDirectory.asFile)
                }
                from(getJavaCompileClasspathProviderByName(unitTestVariant.name))
                from(getJavaCompileDestinationProviderByName(unitTestVariant.name))
            }
            from(getJavaCompileClasspathProviderByName(variant.name))
            from(getJavaCompileDestinationProviderByName(variant.name))
        }

        configureCommonTaskProperties(task, variant.name, combinedTaskClasspath)
    }

    private void configureCommonTaskProperties(PitestTask task, String variantName, FileCollection combinedTaskClasspath) {
        task.with {
            defaultFileForHistoryData.set(new File(project.buildDir, PIT_HISTORY_DEFAULT_FILE_NAME))
            testPlugin.set(pitestExtension.testPlugin)
            reportDir.set(pitestExtension.reportDir.dir(variantName))
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
                String splitter = File.separator.replace("\\", "\\\\")
                FileCollection filteredCombinedTaskClasspath = combinedTaskClasspath.filter { File file ->
                    if (pitestExtension.excludeMockableAndroidJar.getOrElse(false) && file.name == 'android.jar' && file.absolutePath.split(splitter).contains('platforms')) {
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
                JavaCompile javaCompileTask = getJavaCompileTask(project, variantName)
                if (javaCompileTask != null) {
                    additionalMutableCodePaths.add(javaCompileTask.destinationDirectory.asFile)
                }
                Task kotlinCompileTask = project.tasks.findByName("compile${variantName.capitalize()}Kotlin")
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
                project.configurations[PITEST_CONFIGURATION_NAME]
            } as Callable<Configuration>)
            pluginConfiguration.set(pitestExtension.pluginConfiguration)
            maxSurviving.set(pitestExtension.maxSurviving)
            useClasspathJar.set(pitestExtension.useClasspathJar)
            features.set(pitestExtension.features)
            inputEncoding.set(pitestExtension.inputCharset)
            outputEncoding.set(pitestExtension.outputCharset)
        }
    }

    private boolean isBaselineProfileVariantByName(String variantName, String flavorName) {
        String flavoredNonMinified = flavorName ? "${flavorName}NonMinified" : "nonMinified"
        String flavoredBenchmark = flavorName ? "${flavorName}Benchmark" : "benchmark"
        return (project.plugins.hasPlugin("androidx.baselineprofile") && (variantName.startsWith(flavoredNonMinified) || variantName.startsWith(flavoredBenchmark)))
    }

    private boolean isBaselineProfileVariantLegacy(Object variant) {
        return isBaselineProfileVariantByName(variant.name as String, variant.flavorName as String)
    }

    private Provider<FileCollection> getJavaCompileClasspathProviderByName(String variantName) {
        return project.provider {
            JavaCompile task = getJavaCompileTask(project, variantName)
            return task?.classpath ?: project.files()
        }
    }

    private Provider<Directory> getJavaCompileDestinationProviderByName(String variantName) {
        return project.provider {
            JavaCompile task = getJavaCompileTask(project, variantName)
            return task?.destinationDirectory?.get()
        }
    }

    private void addPitDependencies() {
        project.dependencies {
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
    private File getMockableAndroidJar(Object android) {
        boolean returnDefaultValues = android.testOptions.unitTests.returnDefaultValues

        String mockableAndroidJarFilename = "mockable-"
        mockableAndroidJarFilename += sanitizeSdkVersion(android.compileSdkVersion)
        if (returnDefaultValues) {
            mockableAndroidJarFilename += '.default-values'
        }

        File mockableJarDirectory
        if (ANDROID_GRADLE_PLUGIN_VERSION_NUMBER.major >= 3) {
            mockableAndroidJarFilename += '.v3'
            mockableJarDirectory = new File(project.buildDir, "generated")
        } else {
            mockableJarDirectory = new File(project.rootProject.buildDir, "generated")
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
        //Starting with Gradle 8.8.0, Configuration implements "Named" which generates runtime error on "testConfiguration.name" for plugin compiled
        //with 8.8.0+ and executed with lower versions. Keep constant name as workaround
        //Related commit: https://github.com/gradle/gradle/commit/61220ea4fdb30b5c7265dd41e7ac4d70896c957b
        final String testImplementationConfigurationName = "testImplementation"

        project.configurations.named(testImplementationConfigurationName).configure { testImplementation ->
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
