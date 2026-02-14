package pl.droidsonroids.gradle.pitest.functional

import groovy.transform.CompileDynamic
import nebula.test.functional.ExecutionResult

import java.nio.file.Paths

@CompileDynamic
class DynamicFeatureFunctionalSpec extends AbstractPitestFunctionalSpec {

    private String htmlReport = null

    void "should apply pitest plugin to app module without errors"() {
        given:
            copyResources("testProjects/dynamicFeature", "")
        when:
            ExecutionResult result = runTasksSuccessfully(  ":app:pitestRelease")
        then:
            result.wasExecuted(":app:pitestRelease")
            result.standardOutput.contains("Generated 4 mutations Killed 4 (100%)")
    }

    void "should apply pitest plugin to dynamic feature module without errors"() {
        given:
            copyResources("testProjects/dynamicFeature", "")
        when:
            ExecutionResult result = runTasksSuccessfully(":dynamic_feature:pitestRelease")
        then:
            result.wasExecuted(":dynamic_feature:pitestRelease")
            result.standardOutput.contains("Generated 2 mutations Killed 2 (100%)")
    }

    void "should aggregate reports without errors"() {
        given:
            copyResources("testProjects/dynamicFeature", "")
        when:
            ExecutionResult result = runTasksSuccessfully("pitestRelease", "pitestReportAggregate")
        then:
            result.wasExecuted(":app:pitestRelease")
            result.wasExecuted(":dynamic_feature:pitestRelease")
            result.wasExecuted(":pitestReportAggregate")
        and:
            result.standardOutput.contains('Aggregating pitest reports')
            result.standardOutput.contains("Aggregated report ${getOutputReportPath()}")
            fileExists("build/reports/pitest/index.html")
        and:
            assertHtmlContains("<h1>Pit Test Coverage Report</h1>")
            assertHtmlContains("<th>Number of Classes</th>")
            assertHtmlContains("<th>Line Coverage</th>")
            assertHtmlContains("<th>Mutation Coverage</th>")
            assertHtmlContains("<th>Test Strength</th>")
            assertHtmlContains("<td>2</td>")
            assertHtmlContains("<td>100% ")
            assertHtmlContains("<td>100% ")
            assertHtmlContains("<td>100% ")
            assertHtmlContains("<td><a href=\"./pitest.sample.dynamicfeature.app/index.html\">pitest.sample.dynamicfeature.app</a></td>")
            assertHtmlContains("<td><a href=\"./pitest.sample.dynamicfeature.dynamic/index.html\">pitest.sample.dynamicfeature.dynamic</a></td>")
    }

    private void assertHtmlContains(String content) {
        if (htmlReport == null) {
            htmlReport = new File(projectDir, "build/reports/pitest/index.html").text
        }
        assert htmlReport.contains(content)
    }

    private String getOutputReportPath() {
        return Paths.get(projectDir.absolutePath, "build", "reports", "pitest", "index.html").toString()
    }

}
