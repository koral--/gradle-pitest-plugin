package info.solidsoft.gradle.pitest.functional

import com.google.common.base.Predicate
import com.google.common.base.Predicates
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import info.solidsoft.gradle.pitest.PitestPlugin
import nebula.test.functional.ExecutionResult
import nebula.test.functional.GradleRunner
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.internal.jvm.Jvm
import org.gradle.util.GradleVersion
import org.spockframework.runtime.extension.builtin.PreconditionContext
import spock.lang.IgnoreIf
import spock.util.Exceptions

import java.util.regex.Pattern

import static info.solidsoft.gradle.pitest.PitestTaskConfigurationSpec.PIT_PARAMETERS_NAMES_NOT_SET_BY_DEFAULT

/**
 * TODO: Possible extensions:
 *  - Move functional tests to a separate sourceSet and not run them in every build - DONE
 *  - Add nice gradle.build builder
 *  - Add Connector clean up in tear down in IntegrationSpec - DONE
 *  - Add testing against latest nightly Gradle version?
 */
@Slf4j
@SuppressWarnings("GrMethodMayBeStatic")
@CompileDynamic
class PitestPluginGradleVersionFunctionalSpec extends AbstractPitestFunctionalSpec {

    private static final Map<JavaVersion, GradleVersion> MINIMAL_GRADLE_VERSION_FOR_JAVA_VERSION = [
        (JavaVersion.VERSION_15): GradleVersion.version("6.7"),   //https://docs.gradle.org/6.7/release-notes.html
        (JavaVersion.VERSION_16): GradleVersion.version("7.0.2"),   //https://docs.gradle.org/7.0/release-notes.html
        (JavaVersion.VERSION_17): GradleVersion.version("7.0.2"),   //TODO: 7.0, 7.1, 7.2? Determine once testing with Java 17 using toolchain is unlocked - https://github.com/szpak/gradle-pitest-plugin/issues/298
    ].withDefault { GradleVersion.version(GRADLE7_VERSIONS.first()) }

    void setup() {
        daemonMaxIdleTimeInSecondsInMemorySafeMode = 1  //trying to mitigate "Gradle killed" issues with Travis
    }

    void "should run mutation analysis with Gradle #requestedGradleVersion"() {
        given:
            gradleVersion = requestedGradleVersion
            classpathFilter = Predicates.and(GradleRunner.CLASSPATH_DEFAULT, FILTER_SPOCK_JAR)
        when:
            copyResources("testProjects/simple1", "")
        then:
            fileExists('build.gradle')
        when:
            ExecutionResult result = runTasksSuccessfully('pitest', '--warning-mode', 'all')
        then:
            result.wasExecuted(':pitest')
            result.standardOutput.contains('Generated 1 mutations Killed 1 (100%)')
        and:    //issue with Gradle <5.0 where Integer/Boolean property had 0/false provided by default
            //TODO: verifyAll would be great, but it's broken with explicit "assert" - https://github.com/spockframework/spock/issues/855#issuecomment-528411874
            PIT_PARAMETERS_NAMES_NOT_SET_BY_DEFAULT.each { parameterName ->
                assert !result.standardOutput.contains("${parameterName}=")
            }
        where:
            requestedGradleVersion << applyJavaCompatibilityAdjustment(resolveRequestedGradleVersions()).unique()
    }

    @IgnoreIf({ new PreconditionContext().javaVersion >= 13 })   //There is no unsupported version of Gradle which can be used with Java 13
    void "should fail with meaningful error message with too old Gradle version"() {
        given:
            gradleVersion = "5.5.1"
        and:
            assert PitestPlugin.MINIMAL_SUPPORTED_GRADLE_VERSION > GradleVersion.version(gradleVersion)
        when:
            copyResources("testProjects/simple1", "")
        then:
            fileExists('build.gradle')
        when:
            ExecutionResult result = runTasksWithFailure('tasks')
        then:
            verifyAll {
                Throwable root = Exceptions.getRootCause(result.failure)
                root.class.name == GradleException.name //name to mitigate differences on classloader
                root.message.contains("'info.solidsoft.pitest' requires")
                result.standardOutput.contains("WARNING. The 'info.solidsoft.pitest' plugin requires")
            }
    }

    //To prevent failure when Spock for Groovy 2.5 is run with Groovy 3.0 delivered with Gradle 7+
    //Spock is not needed in this artificial project - just the test classpath leaks to Gradle instance started by Nebula
    private static final Pattern SPOCK_JAR_PATTERN = Pattern.compile(".*spock-core-2\\..*.jar")
    private static final Predicate<URL> FILTER_SPOCK_JAR = { URL url ->
        return !url.toExternalForm().matches(SPOCK_JAR_PATTERN)
    } as Predicate<URL>

    //TODO: Extract regression tests control mechanism to a separate class (or even better trait) when needed in some other place
    private static final String REGRESSION_TESTS_ENV_NAME = "PITEST_REGRESSION_TESTS"
    private static final List<String> GRADLE6_VERSIONS = ["6.9.1", "6.8.3", "6.7", "6.6", "6.5", PitestPlugin.MINIMAL_SUPPORTED_GRADLE_VERSION.version]
    private static final List<String> GRADLE7_VERSIONS = ["7.2", "7.1.1", "7.0.2"]
    private static final List<String> GRADLE_LATEST_VERSIONS = [GRADLE6_VERSIONS.first(), GRADLE7_VERSIONS.first(), PitestPlugin.MINIMAL_SUPPORTED_GRADLE_VERSION.version]

    private List<String> resolveRequestedGradleVersions() {
        String regressionTestsLevel = System.getenv(REGRESSION_TESTS_ENV_NAME)
        log.debug("$REGRESSION_TESTS_ENV_NAME set to '${regressionTestsLevel}'")
        switch (regressionTestsLevel) {
            case "latestOnly":
            case null:
                return GRADLE_LATEST_VERSIONS
            case "quick":
                return GRADLE_LATEST_VERSIONS
            case "full":
                return GRADLE6_VERSIONS + GRADLE7_VERSIONS
            default:
                log.warn("Unsupported $REGRESSION_TESTS_ENV_NAME value '`$regressionTestsLevel`' (expected 'latestOnly', 'quick' or 'full'). " +
                    "Assuming 'latestOnly'.")
                return GRADLE_LATEST_VERSIONS
        }
    }

    //Jvm class from Spock doesn't work with Java 9 stable releases - otherwise @IgnoreIf could be used - TODO: check with Spock 1.2
    @SuppressWarnings("ConfusingTernary")
    private List<String> applyJavaCompatibilityAdjustment(List<String> requestedGradleVersions) {
        if (!Jvm.current().javaVersion.isCompatibleWith(JavaVersion.VERSION_15)) {
            //All supported versions should be Java 14 compatible
            return requestedGradleVersions
        }
        GradleVersion minimalCompatibleGradleVersion = MINIMAL_GRADLE_VERSION_FOR_JAVA_VERSION.get(Jvm.current().javaVersion)
        return leaveJavaXCompatibleGradleVersionsOnly(requestedGradleVersions, minimalCompatibleGradleVersion)
    }

    private List<String> leaveJavaXCompatibleGradleVersionsOnly(List<String> requestedGradleVersions, GradleVersion minimalCompatibleJavaVersion) {
        List<String> javaXCompatibleGradleVersions = requestedGradleVersions.findAll { version ->
            GradleVersion.version(version) >= minimalCompatibleJavaVersion
        }
        if (javaXCompatibleGradleVersions.size() < 2) {
            javaXCompatibleGradleVersions.add(minimalCompatibleJavaVersion.version)
        }
        return javaXCompatibleGradleVersions
    }

}
