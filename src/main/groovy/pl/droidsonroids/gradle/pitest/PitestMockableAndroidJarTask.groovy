package pl.droidsonroids.gradle.pitest

import com.android.builder.testing.MockableJarGenerator
import groovy.transform.CompileDynamic
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the mockable `android.jar` PIT runs against.
 *
 * All inputs are wired by {@link PitestPlugin} at configuration time. The task deliberately does not touch
 * `Task.project`, which is unsupported at execution time with the Gradle configuration cache.
 */
@CompileDynamic
abstract class PitestMockableAndroidJarTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getInputJar()

    @OutputFile
    abstract RegularFileProperty getOutputJar()

    @Input
    abstract Property<Boolean> getReturnDefaultValues()

    @TaskAction
    @SuppressWarnings("BuilderMethodWithSideEffects")
    protected void createMockableAndroidJar() {
        File outputJarFile = outputJar.get().asFile
        if (!outputJarFile.parentFile.mkdirs() && !outputJarFile.parentFile.isDirectory()) {
            throw new IOException("Could not create directory at ${outputJarFile.parentFile}")
        }

        if (outputJarFile.isFile()) {
            outputJarFile.delete()
        }

        MockableJarGenerator generator = new MockableJarGenerator(returnDefaultValues.get())
        generator.createMockableJar(inputJar.get().asFile, outputJarFile)
    }

}
