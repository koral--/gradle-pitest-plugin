package pl.droidsonroids.gradle.pitest

import groovy.transform.CompileDynamic
import org.gradle.api.Project
import org.gradle.api.Task
import spock.lang.Issue
import spock.lang.Specification

import java.nio.charset.StandardCharsets

@CompileDynamic
@SuppressWarnings("PrivateFieldCouldBeFinal")
class PitestAggregatorPluginTest extends Specification {

    private Project project = AndroidUtils.projectBuilder().build()

    void "add aggregate report task to project in proper group"() {
        when:
            project.pluginManager.apply(PitestAggregatorPlugin.PLUGIN_ID)
        then:
            project.plugins.hasPlugin(PitestAggregatorPlugin)
            assertThatTasksAreInGroup([PitestAggregatorPlugin.PITEST_REPORT_AGGREGATE_TASK_NAME], PitestPlugin.PITEST_TASK_GROUP)
    }

    void "use default pitest version by default"() {
        when:
            project.pluginManager.apply(PitestAggregatorPlugin.PLUGIN_ID)
        and:
            triggerEvaluateForAggregateTask()
        then:
            project.configurations.named("pitestReport").get().incoming.dependencies.find { dep ->
                dep.version == PitestPlugin.DEFAULT_PITEST_VERSION
            }
    }

    void "use pitest version defined in main configuration in the same project"() {
        given:
            String testPitestVersion = "1.0.99"
            project.pluginManager.apply("java")
            project.pluginManager.apply(PitestPlugin.PLUGIN_ID)
            project.extensions.findByType(PitestPluginExtension).pitestVersion.set(testPitestVersion)
        when:
            project.pluginManager.apply(PitestAggregatorPlugin.PLUGIN_ID)
        and:
            triggerEvaluateForAggregateTask()
        then:
            project.configurations.named(PitestAggregatorPlugin.PITEST_REPORT_AGGREGATE_CONFIGURATION_NAME).get().incoming.dependencies.find { dep ->
                dep.version == testPitestVersion
            }
    }

    void "expose configured charsets as strings in aggregate task inputs"() {
        given:
            project.pluginManager.apply("java")
            project.pluginManager.apply(PitestPlugin.PLUGIN_ID)
            PitestPluginExtension extension = project.extensions.findByType(PitestPluginExtension)
            extension.inputCharset.set(StandardCharsets.UTF_8)
            extension.outputCharset.set(StandardCharsets.ISO_8859_1)
        when:
            project.pluginManager.apply(PitestAggregatorPlugin.PLUGIN_ID)
        and:
            Map<String, Object> inputProperties = project.tasks
                .named(PitestAggregatorPlugin.PITEST_REPORT_AGGREGATE_TASK_NAME).get().inputs.properties
        then:
            inputProperties['inputCharsetString'] == "UTF-8"
            inputProperties['outputCharsetString'] == "ISO-8859-1"
        and:
            !inputProperties.containsKey('inputCharset')
            !inputProperties.containsKey('outputCharset')
    }

    @Issue("https://github.com/szpak/gradle-pitest-plugin/issues/146")
    void "not depend on pitest tasks and collect report files of all variants lazily"() {
        given:
            project = AndroidUtils.createSampleLibraryProject()
            project.pluginManager.apply(PitestAggregatorPlugin.PLUGIN_ID)
        when:
            project.evaluate()
            AggregateReportTask aggregateTask = project.tasks.named(PitestAggregatorPlugin.PITEST_REPORT_AGGREGATE_TASK_NAME).get()
            Set<String> dependencyNames = aggregateTask.taskDependencies.getDependencies(aggregateTask)*.name as Set
            Set<File> mutationFiles = aggregateTask.mutationFiles.files
        then:
            dependencyNames.intersect(['pitest', 'pitestDebug', 'pitestRelease']).isEmpty()
        and:
            mutationFiles.contains(project.file('build/reports/pitest/debug/mutations.xml'))
            mutationFiles.contains(project.file('build/reports/pitest/release/mutations.xml'))
    }

//    void "use pitest version from subproject project configuration"() {}    //TODO: Can be implemented with ProjectBuilder? withParent()?
//    void "use configured charset in aggregation"() {} //TODO: Can be tested in "unit" way?

//    void "use configured charset in aggregation"() {} //TODO: Can be tested in "unit" way?

    private void assertThatTasksAreInGroup(List<String> taskNames, String group) {
        taskNames.each { String taskName ->
            Task task = project.tasks[taskName]
            assert task.group == group
        }
    }

    private void triggerEvaluateForAggregateTask() {
        project.tasks[PitestAggregatorPlugin.PITEST_REPORT_AGGREGATE_TASK_NAME]
    }

}
