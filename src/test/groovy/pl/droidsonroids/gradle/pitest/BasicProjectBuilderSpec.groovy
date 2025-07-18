/* Copyright (c) 2017 Marcin Zajączkowski
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
import groovy.transform.PackageScope
import org.gradle.api.Project
import org.gradle.api.Task
import spock.lang.Specification
import spock.lang.TempDir

import static PitestPlugin.PITEST_TASK_NAME

/**
 * @see WithPitestTaskInitialization
 */
@PackageScope
@CompileDynamic
class BasicProjectBuilderSpec extends Specification {

    @TempDir
    protected File tmpProjectDir

    protected Project project
    protected PitestPluginExtension pitestConfig

    //TODO: There is a regression in 2.14.1 with API jar regeneration for every test - https://discuss.gradle.org/t/performance-regression-in-projectbuilder-in-2-14-and-3-0/18956
    //https://github.com/gradle/gradle/commit/3216f07b3acb4cbbb8241d8a1d50b8db9940f37e
    protected void setup() {
        project = AndroidUtils.createSampleLibraryProject(tmpProjectDir)

        pitestConfig = project.getExtensions().getByType(PitestPluginExtension)

        project.group = 'test.group'
    }

    protected PitestTask getJustOnePitestTaskOrFail() {
        String pitestReleaseTaskName = "${PITEST_TASK_NAME}Release"
        Set<Task> tasks = project.getTasksByName(pitestReleaseTaskName, false)
        //forces "afterEvaluate"
        assert tasks?.size() == 1: "Expected tasks: '$pitestReleaseTaskName', All tasks: ${project.tasks}"
        assert tasks[0] instanceof PitestTask
        return (PitestTask) tasks[0]
    }

}
