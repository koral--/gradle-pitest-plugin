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
import com.vdurmont.semver4j.SemverException
import groovy.transform.CompileDynamic
import groovy.transform.PackageScope
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
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

    public final static String DEFAULT_PITEST_VERSION = '1.22.1'
    public final static String PITEST_TASK_GROUP = VERIFICATION_GROUP
    public final static String PITEST_TASK_NAME = "pitest"
    public final static String PITEST_REPORT_DIRECTORY_NAME = 'pitest'
    public final static String PITEST_CONFIGURATION_NAME = 'pitest'
    public final static String PITEST_TEST_COMPILE_CONFIGURATION_NAME = 'pitestTestCompile'

    private final static int AGP_9_MAJOR_VERSION = 9
    private final static String MOCKABLE_ANDROID_JAR_TASK_NAME = "pitestMockableAndroidJar"
    private final static String KOTLIN_MAIN_COMPILATION_SUFFIX = "Main"
    private final static List<String> ANDROID_PLUGIN_IDS = ["com.android.application", "com.android.library",
                                                            "com.android.dynamic-feature", "com.android.test",
                                                            "com.android.kotlin.multiplatform.library"].asImmutable()
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
        } catch (ReflectiveOperationException | SemverException ignored) {
            try {
                Class<?> clazz = PitestPlugin.classLoader.loadClass("com.android.builder.model.Version")
                return new Semver(clazz.getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null) as String)
            } catch (ReflectiveOperationException | SemverException ignored2) {
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
    private boolean androidPluginApplied
    private final Set<String> loggedWarnings = [] as Set

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
        boolean callbacksRegistered = false

        Action<Plugin> registerVariantCallbacks = {
            androidPluginApplied = true
            if (callbacksRegistered) {
                return
            }
            callbacksRegistered = true
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
                    } catch (MissingPropertyException ignored) {
                        //`getUnitTest()` is declared on `Variant` itself in both AGP 8.5 and AGP 9, so this only
                        //guards against older versions exposing it on a subset of the variant types
                        log.info("Variant '${variant.name}' does not expose unit tests.")
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
        ANDROID_PLUGIN_IDS.each { String pluginId ->
            project.plugins.withId(pluginId, registerVariantCallbacks)
        }

        project.afterEvaluate {
            //without an Android plugin there are no variants to attach Pitest tasks to, and the new variant API path
            //would otherwise create a `pitest` task which silently does nothing
            if (!androidPluginApplied) {
                throw new GradleException("No Android plugin found in project '${project.path}'. " +
                        "One of ${ANDROID_PLUGIN_IDS} has to be applied together with the Pitest plugin.")
            }

            //has to run after the Android plugin created the test configurations, which is not guaranteed when this
            //plugin is applied before `com.android.*`, but still before any of them gets copied for the Pitest classpath
            addJUnitPlatformLauncherDependencyIfNeeded()

            Object androidSourceSets = project.extensions.findByName("android")?.sourceSets
            setDefaultSourceSets(pitestExtension.mainSourceSets, androidSourceSets, "main")
            setDefaultSourceSets(pitestExtension.testSourceSets, androidSourceSets, "test")

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

    //`SetProperty.empty()` *sets* the property to an empty collection and returns the property itself, whose Groovy
    //truth is not an emptiness check, so it cannot tell an unset property from a user configured one. The convention
    //is a `null` provider (see `PitestPluginExtension`), which makes an unconfigured property simply not present.
    private void setDefaultSourceSets(SetProperty<AndroidSourceSet> sourceSetsProperty, Object androidSourceSets, String name) {
        if (sourceSetsProperty.isPresent()) {
            return
        }
        Object androidSourceSet = androidSourceSets?.findByName(name)
        if (androidSourceSet == null) {
            warnOnce("Android source set '${name}' not found, no ${name} source directories will be passed to PIT. " +
                    "Set 'pitest.${name}SourceSets' explicitly if the sources live elsewhere.")
            sourceSetsProperty.set([] as Set<AndroidSourceSet>)
        } else {
            sourceSetsProperty.set([androidSourceSet] as Set<AndroidSourceSet>)
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

        //`PitestMockableAndroidJarTask.outputJar` resolves the platform `android.jar` under the new DSL, so neither the
        //task nor the read may happen when the user excluded the mockable JAR to avoid exactly that
        Provider<RegularFile> mockableAndroidJar = null
        if (!pitestExtension.excludeMockableAndroidJar.getOrElse(false)) {
            addMockableAndroidJarDependencies()
            Task mockableAndroidJarTask = createMockableAndroidJarTask()
            mockableAndroidJar = mockableAndroidJarTask.outputJar
            variantTask.dependsOn mockableAndroidJarTask
        }
        configureTaskDefaultByName(variantTask, variantName, variantDirName, unitTestName, mockableAndroidJar)

        variantTask.with {
            description = "Run PIT analysis for java classes, for ${variantName} build variant"
            group = PITEST_TASK_GROUP
            //`debugUnitTest` -> `testDebugUnitTest`, `androidHostTest` -> `testAndroidHostTest`; resolved lazily because
            //naming a task that does not exist makes `shouldRunAfter` throw
            if (unitTestName != null) {
                shouldRunAfter { project.tasks.findByName("test${unitTestName.capitalize()}") ?: [] }
            }
        }
        suppressPassingDeprecatedTestPluginForNewerPitVersions(variantTask)

        //resolved lazily, the tasks may not exist yet when this plugin is applied before `com.android.*`
        //variants without a unit test component (`com.android.test` modules) have nothing to compile here
        if (unitTestName != null) {
            variantTask.dependsOn {
                String unitTestSourcesTaskName = "compile${unitTestName.capitalize()}Sources"
                Task unitTestSourcesTask = project.tasks.findByName(unitTestSourcesTaskName)
                if (unitTestSourcesTask == null) {
                    warnOnce("Task '${unitTestSourcesTaskName}' not found, '${variantTask.name}' may be executed " +
                            "against stale or missing unit test classes.")
                }
                return unitTestSourcesTask ?: []
            }
        }
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
                mockableAndroidJarTask = createMockableAndroidJarTask()
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

    //`PitestMockableAndroidJarTask` must not read `Task.project` at execution time (unsupported with the configuration
    //cache), so everything it needs is wired here, lazily: the providers are only resolved once the task is in the
    //task graph, which keeps the platform `android.jar` lookup out of `gradlew help` and friends
    //Kotlin Multiplatform Android targets name their compile tasks after the compilation (`compileAndroidMain`,
    //`compileAndroidHostTest`), classic Android modules after the variant (`compileDebugKotlin`)
    private Task findKotlinCompileTask(String name) {
        Task kotlinCompileTask = project.tasks.findByName("compile${name.capitalize()}Kotlin")
        if (kotlinCompileTask != null) {
            return kotlinCompileTask
        }
        Task multiplatformCompileTask = project.tasks.findByName("compile${name.capitalize()}")
        return multiplatformCompileTask?.hasProperty("destinationDirectory") ? multiplatformCompileTask : null
    }

    //classic Android modules have `<variant>RuntimeClasspath`, a Kotlin Multiplatform Android target names its
    //configurations after the target instead of the `<target>Main` compilation its variant is named for, so the
    //counterpart of `androidMainRuntimeClasspath` is `androidRuntimeClasspath`
    private Configuration findRuntimeClasspathConfiguration(String name) {
        Configuration runtimeClasspath = project.configurations.findByName("${name}RuntimeClasspath")
        if (runtimeClasspath == null && name.endsWith(KOTLIN_MAIN_COMPILATION_SUFFIX)) {
            String targetName = name.substring(0, name.length() - KOTLIN_MAIN_COMPILATION_SUFFIX.length())
            runtimeClasspath = project.configurations.findByName("${targetName}RuntimeClasspath")
        }
        return runtimeClasspath
    }

    //`AndroidSourceSet.kotlin` does not exist in AGP 3.x/4.x, which the legacy path still supports, and Kotlin
    //Multiplatform Android targets have no `AndroidSourceSet` at all - their sources live in `kotlin.sourceSets`
    private Set<File> resolveSourceDirs(Set<AndroidSourceSet> androidSourceSets, String kotlinSourceSetName) {
        Set<File> resolvedSourceDirs = [] as Set
        androidSourceSets.each { AndroidSourceSet androidSourceSet ->
            resolvedSourceDirs.addAll(androidSourceSet.java.srcDirs)
            resolvedSourceDirs.addAll(androidSourceSet.resources.srcDirs)
            if (androidSourceSet.hasProperty("kotlin")) {
                resolvedSourceDirs.addAll(androidSourceSet.kotlin.srcDirs)
            }
        }
        if (resolvedSourceDirs.isEmpty() && kotlinSourceSetName != null) {
            resolvedSourceDirs.addAll(kotlinSourceDirs(kotlinSourceSetName))
        }
        return resolvedSourceDirs
    }

    //a Kotlin Multiplatform source set carries only its own sources, the shared ones come from the source sets it
    //`dependsOn` (`androidMain` -> `commonMain`, `androidHostTest` -> `commonTest`), so the chain has to be walked
    private Set<File> kotlinSourceDirs(String sourceSetName) {
        Object rootSourceSet = project.extensions.findByName("kotlin")?.sourceSets?.findByName(sourceSetName)
        if (rootSourceSet == null) {
            return [] as Set
        }
        Set<Object> collectedSourceSets = [] as Set
        collectKotlinSourceSets(rootSourceSet, collectedSourceSets)
        Set<File> resolvedSourceDirs = [] as Set
        collectedSourceSets.each { Object kotlinSourceSet ->
            resolvedSourceDirs.addAll(kotlinSourceSet.kotlin.srcDirs)
            resolvedSourceDirs.addAll(kotlinSourceSet.resources.srcDirs)
        }
        return resolvedSourceDirs
    }

    private static void collectKotlinSourceSets(Object kotlinSourceSet, Set<Object> collectedSourceSets) {
        if (!collectedSourceSets.add(kotlinSourceSet)) {
            return
        }
        kotlinSourceSet.dependsOn.each { Object parentSourceSet ->
            collectKotlinSourceSets(parentSourceSet, collectedSourceSets)
        }
    }

    @SuppressWarnings("BuilderMethodWithSideEffects")
    private Task createMockableAndroidJarTask() {
        Task existingTask = project.tasks.findByName(MOCKABLE_ANDROID_JAR_TASK_NAME)
        if (existingTask != null) {
            return existingTask
        }

        Object android = project.extensions.findByName("android")
        PitestMockableAndroidJarTask task = project.tasks.create(MOCKABLE_ANDROID_JAR_TASK_NAME, PitestMockableAndroidJarTask)
        boolean returnDefaultValues = android?.testOptions?.unitTests?.returnDefaultValues ?: false
        String suffix = returnDefaultValues ? "-default-values" : ""

        task.returnDefaultValues.set(returnDefaultValues)
        task.inputJar.fileProvider(androidPlatformJarProvider(android))
        task.outputJar.set(project.layout.buildDirectory.file(compileSdkNameProvider(android).map { String compileSdkName ->
            return "pitest-${sanitizeSdkVersion(compileSdkName)}${suffix}.jar"
        }))
        return task
    }

    //`sdkDirectory` and `compileSdkVersion` exist only on the legacy `BaseExtension`, the new `CommonExtension` DSL
    //(the only one available in AGP 9) exposes the platform `android.jar` through `SdkComponents.bootClasspath` instead
    private Provider<File> androidPlatformJarProvider(Object android) {
        if (android?.hasProperty("sdkDirectory") && android.sdkDirectory != null &&
                android.hasProperty("compileSdkVersion") && android.compileSdkVersion != null) {
            File androidJar = new File("${android.sdkDirectory}/platforms/${android.compileSdkVersion}/android.jar")
            return project.providers.provider { androidJar }
        }
        return androidJarFromSdkComponents()
    }

    private Provider<String> compileSdkNameProvider(Object android) {
        String compileSdkName = null
        if (android?.hasProperty("compileSdkVersion") && android.compileSdkVersion != null) {
            compileSdkName = android.compileSdkVersion as String
        } else if (android?.hasProperty("compileSdk") && android.compileSdk != null) {
            compileSdkName = "android-${android.compileSdk}"
        } else if (android?.hasProperty("compileSdkPreview") && android.compileSdkPreview != null) {
            compileSdkName = android.compileSdkPreview as String
        }
        if (compileSdkName != null) {
            String resolvedCompileSdkName = compileSdkName
            return project.providers.provider { resolvedCompileSdkName }
        }
        //the platform `android.jar` lives in `<sdk>/platforms/<compileSdkVersion>/`
        return androidJarFromSdkComponents().map { File androidJar -> androidJar.parentFile.name }
    }

    private Provider<File> androidJarFromSdkComponents() {
        AndroidComponentsExtension androidComponents = project.extensions.findByType(AndroidComponentsExtension)
        if (androidComponents == null) {
            throw new GradleException("Cannot locate the Android platform JAR, " +
                    "no Android plugin applied to project '${project.path}'")
        }
        return androidComponents.sdkComponents.bootClasspath.map { List<RegularFile> bootClasspath ->
            File androidJar = bootClasspath*.asFile.find { File file -> file.name == 'android.jar' }
            if (androidJar == null) {
                throw new GradleException("Cannot locate 'android.jar' in the Android boot classpath: ${bootClasspath*.asFile}")
            }
            return androidJar
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
    private void configureTaskDefaultByName(PitestTask task, String variantName, String dirName, String unitTestName, Provider<RegularFile> mockableAndroidJar) {
        FileCollection combinedTaskClasspath = project.files()

        combinedTaskClasspath.with {
            from(project.configurations[PITEST_TEST_COMPILE_CONFIGURATION_NAME])
            if (!pitestExtension.excludeMockableAndroidJar.getOrElse(false)) {
                from(mockableAndroidJar)
            }

            if (project.findProperty("android.enableJetifier") != "true") {
                Configuration runtimeConfig = findRuntimeClasspathConfiguration(variantName)
                if (runtimeConfig != null) {
                    //`ProjectDependency.getDependencyProject()` was removed in Gradle 9, which AGP 9 requires, so the
                    //duck-typed `dependency.properties.dependencyProject` lookup used before always evaluated to `null`
                    //there and let project dependencies into the copy, where they cannot be resolved
                    Configuration copiedRuntimeConfig = runtimeConfig.copyRecursive { dependency ->
                        !ProjectDependency.isInstance(dependency) && dependency.version != null
                    }.shouldResolveConsistentlyWith(runtimeConfig)

                    from(copiedRuntimeConfig.incoming.artifactView { view ->
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)

                    //`copyRecursive` drops project dependencies, and a Kotlin Multiplatform Android target has no
                    //`compile<Variant>JavaWithJavac` whose classpath would carry them instead, so its own classes and
                    //its siblings' would be missing entirely. These configurations also cannot be resolved without
                    //asking for an artifact type, their project artifacts are ambiguous otherwise.
                    from(runtimeConfig.incoming.artifactView { view ->
                        view.lenient(true)
                        view.componentFilter { identifier -> ProjectComponentIdentifier.isInstance(identifier) }
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)
                }

                //`debugUnitTest` -> `debugUnitTestRuntimeClasspath`, `androidHostTest` -> `androidHostTestRuntimeClasspath`
                Configuration unittestRuntimeConfig = unitTestName == null ? null
                        : findRuntimeClasspathConfiguration(unitTestName)
                if (unittestRuntimeConfig != null) {
                    Configuration copiedUnittestRuntimeConfig = unittestRuntimeConfig.copyRecursive { dependency ->
                        !ProjectDependency.isInstance(dependency) && dependency.version != null
                    }.shouldResolveConsistentlyWith(unittestRuntimeConfig)

                    from(copiedUnittestRuntimeConfig.incoming.artifactView { view ->
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)

                    //`copyRecursive` drops project dependencies, and a Kotlin Multiplatform Android target has no
                    //`compile<Variant>JavaWithJavac` whose classpath would carry them instead, so its own classes and
                    //its siblings' would be missing entirely. These configurations also cannot be resolved without
                    //asking for an artifact type, their project artifacts are ambiguous otherwise.
                    from(unittestRuntimeConfig.incoming.artifactView { view ->
                        view.lenient(true)
                        view.componentFilter { identifier -> ProjectComponentIdentifier.isInstance(identifier) }
                        view.attributes { attrs ->
                            attrs.attribute(Attribute.of("artifactType", String), "jar")
                        }
                    }.files)
                } else {
                    log.info("No unit test runtime classpath configuration found for variant '${variantName}' " +
                            "(it may not have unit tests enabled)")
                }
            }
            from(project.configurations["pitestRuntimeOnly"])
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/${dirName}"))
            from(project.files("${project.buildDir}/intermediates/sourceFolderJavaResources/test/${dirName}"))
            from(project.files("${project.buildDir}/intermediates/java_res/${dirName}/out"))
            from(project.files("${project.buildDir}/intermediates/java_res/${dirName}UnitTest/out"))
            from(project.files("${project.buildDir}/intermediates/unitTestConfig/test/${dirName}"))
            from { findKotlinCompileTask(variantName)?.destinationDirectory?.asFile }

            if (unitTestName != null) {
                from { findKotlinCompileTask(unitTestName)?.destinationDirectory?.asFile }
                from(getJavaCompileClasspathProviderByName(unitTestName))
                from(getJavaCompileDestinationProviderByName(unitTestName))
            }
            from(getJavaCompileClasspathProviderByName(variantName))
            from(getJavaCompileDestinationProviderByName(variantName))
        }

        configureCommonTaskProperties(task, variantName, unitTestName, combinedTaskClasspath)
    }

    //`mockableAndroidJar` is either a `File` (AGP below 3.2) or a `Provider<RegularFile>` of the generated mockable
    //JAR, both of which `from` accepts
    @SuppressWarnings(["Instanceof", "UnnecessarySetter", "DuplicateNumberLiteral"])
    private void configureTaskDefaultLegacy(PitestTask task, Object variant, Object mockableAndroidJar) {
        String unitTestName = null
        try {
            unitTestName = variant.unitTestVariant?.name
        } catch (MissingPropertyException ignored) {
            //the variant may not support unit tests at all
        }
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
                        !ProjectDependency.isInstance(dependency)
                    })
                    from(project.configurations["${variant.name}UnitTestCompileClasspath"].copyRecursive { dependency ->
                        !ProjectDependency.isInstance(dependency)
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
                        !ProjectDependency.isInstance(dependency) && dependency.version != null
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
                        !ProjectDependency.isInstance(dependency) && dependency.version != null
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

            if (unitTestName != null) {
                Task testKotlinCompileTask = project.tasks.findByName("compile${unitTestName.capitalize()}Kotlin")
                if (testKotlinCompileTask != null) {
                    from(testKotlinCompileTask.destinationDirectory.asFile)
                }
                from(getJavaCompileClasspathProviderByName(unitTestName))
                from(getJavaCompileDestinationProviderByName(unitTestName))
            }
            from(getJavaCompileClasspathProviderByName(variant.name))
            from(getJavaCompileDestinationProviderByName(variant.name))
        }

        configureCommonTaskProperties(task, variant.name, unitTestName, combinedTaskClasspath)
    }

    private void configureCommonTaskProperties(PitestTask task, String variantName, String unitTestName, FileCollection combinedTaskClasspath) {
        task.with {
            defaultFileForHistoryData.set(new File(project.layout.buildDirectory.asFile.get(), PIT_HISTORY_DEFAULT_FILE_NAME))
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
            Set<File> mainSourceDirs = resolveSourceDirs(pitestExtension.mainSourceSets.getOrElse([] as Set), variantName)
            sourceDirs.setFrom({
                //PIT exits with 0 on `Missing required option(s) [sourceDirs]`, so without this the task would report
                //success without having run any analysis at all
                if (mainSourceDirs.isEmpty()) {
                    throw new GradleException("No source directories found for variant '${variantName}'. " +
                            "Set 'pitest.mainSourceSets' explicitly (Android modules) or make sure the Kotlin source " +
                            "set '${variantName}' exists (Kotlin Multiplatform modules), PIT cannot run without them.")
                }
                return mainSourceDirs
            } as Callable<Set<File>>)
            detectInlinedCode.set(pitestExtension.detectInlinedCode)
            timestampedReports.set(pitestExtension.timestampedReports)
            Set<File> testSourceDirs = resolveSourceDirs(pitestExtension.testSourceSets.getOrElse([] as Set), unitTestName)
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
            } as Callable<FileCollection>, testSourceDirs)
            useAdditionalClasspathFile.set(pitestExtension.useClasspathFile)
            additionalClasspathFile.set(new File(project.layout.buildDirectory.asFile.get(), PIT_ADDITIONAL_CLASSPATH_DEFAULT_FILE_NAME))
            mutableCodePaths.setFrom({
                Set<Object> additionalMutableCodePaths = [] as Set
                if (pitestExtension.additionalMutableCodePaths.isPresent()) {
                    additionalMutableCodePaths.addAll(pitestExtension.additionalMutableCodePaths.get())
                }
                JavaCompile javaCompileTask = findJavaCompileTask(variantName)
                if (javaCompileTask != null) {
                    additionalMutableCodePaths.add(javaCompileTask.destinationDirectory.asFile)
                }
                Task kotlinCompileTask = findKotlinCompileTask(variantName)
                if (kotlinCompileTask != null) {
                    additionalMutableCodePaths.add(kotlinCompileTask.destinationDirectory.asFile)
                }
                //without any of them PIT would report a successful run with zero mutations instead of failing
                if (additionalMutableCodePaths.isEmpty()) {
                    throw new GradleException("Neither 'compile${variantName.capitalize()}JavaWithJavac' nor " +
                            "'compile${variantName.capitalize()}Kotlin' nor 'compile${variantName.capitalize()}' " +
                            "found, there is no compiled code of variant '${variantName}' to mutate. " +
                            "Set 'pitest.additionalMutableCodePaths' explicitly if the classes are produced by " +
                            "other tasks.")
                }
                return additionalMutableCodePaths
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

    //the task does not exist for every variant and is looked up by name, so all three call sites have to agree on
    //what a miss means, otherwise the same cause surfaces as an empty classpath here and as a provider without a value
    //(`Cannot query the value of this provider`) there
    private JavaCompile findJavaCompileTask(String variantName) {
        JavaCompile javaCompileTask = getJavaCompileTask(project, variantName)
        if (javaCompileTask == null) {
            warnOnce("Task 'compile${variantName.capitalize()}JavaWithJavac' not found, Java classes of variant " +
                    "'${variantName}' will not be put on the Pitest classpath nor mutated.")
        }
        return javaCompileTask
    }

    private void warnOnce(String message) {
        if (loggedWarnings.add(message)) {
            log.warn(message)
        }
    }

    private Provider<FileCollection> getJavaCompileClasspathProviderByName(String variantName) {
        return project.provider {
            JavaCompile task = findJavaCompileTask(variantName)
            return task?.classpath ?: project.files()
        }
    }

    private Provider<FileCollection> getJavaCompileDestinationProviderByName(String variantName) {
        return project.provider {
            JavaCompile task = findJavaCompileTask(variantName)
            return task != null ? project.files(task.destinationDirectory) : project.files()
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
            mockableJarDirectory = new File(project.layout.buildDirectory.asFile.get(), "generated")
        } else {
            mockableJarDirectory = new File(project.rootProject.layout.buildDirectory.asFile.get(), "generated")
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

        //not every Android module has it, e.g. Kotlin Multiplatform Android targets use `androidHostTest*` instead
        if (!project.configurations.names.contains(testImplementationConfigurationName)) {
            log.info("Configuration '${testImplementationConfigurationName}' not found, junit-platform-launcher will " +
                    "not be added. Add it manually if PIT reports 'Minion exited abnormally due to UNKNOWN_ERROR'.")
            return
        }

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

                final String testRuntimeOnlyConfigurationName = "testRuntimeOnly"
                if (!project.configurations.names.contains(testRuntimeOnlyConfigurationName)) {
                    log.info("Configuration '${testRuntimeOnlyConfigurationName}' not found, junit-platform-launcher " +
                            "will not be added.")
                    return
                }

                String junitPlatformLauncherDependencyAsString = "${orgJUnitPlatformGroup}:junit-platform-launcher:${foundJunitPlatformComponent.moduleVersion.version}"
                log.info("${orgJUnitPlatformGroup} component (${foundJunitPlatformComponent}) found in ${testImplementation.name}, " +
                        "adding junit-platform-launcher (${junitPlatformLauncherDependencyAsString}) to ${testRuntimeOnlyConfigurationName}")
                project.configurations.named(testRuntimeOnlyConfigurationName).configure({ Configuration testRuntimeOnly ->
                    testRuntimeOnly.dependencies.add(project.dependencies.create(junitPlatformLauncherDependencyAsString))
                } as Action<Configuration>)
            }
        }
    }

}
