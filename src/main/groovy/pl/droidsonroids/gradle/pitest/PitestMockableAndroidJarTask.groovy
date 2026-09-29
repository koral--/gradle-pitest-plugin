package pl.droidsonroids.gradle.pitest

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.builder.testing.MockableJarGenerator
import groovy.transform.CompileDynamic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CompileDynamic
class PitestMockableAndroidJarTask extends DefaultTask {

    //`sdkDirectory` and `compileSdkVersion` exist only on the legacy `BaseExtension`, the new `CommonExtension` DSL
    //(the only one available in AGP 9) exposes the platform `android.jar` through `SdkComponents.bootClasspath` instead
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    File getInputJar() {
        Object android = project.extensions.findByName("android")
        if (android?.hasProperty("sdkDirectory") && android?.hasProperty("compileSdkVersion")) {
            return new File("${android.sdkDirectory}/platforms/${android.compileSdkVersion}/android.jar")
        }
        return androidJarFromSdkComponents()
    }

    @OutputFile
    File getOutputJar() {
        String suffix = returnDefaultValues ? "-default-values" : ""
        String outputJarFilename = "pitest-${PitestPlugin.sanitizeSdkVersion(compileSdkName)}${suffix}.jar"
        return new File(project.buildDir, outputJarFilename)
    }

    @Internal
    protected boolean isReturnDefaultValues() {
        return project.extensions.findByName("android").testOptions.unitTests.returnDefaultValues
    }

    @Internal
    protected String getCompileSdkName() {
        Object android = project.extensions.findByName("android")
        if (android?.hasProperty("compileSdkVersion")) {
            return android.compileSdkVersion as String
        }
        //the platform `android.jar` lives in `<sdk>/platforms/<compileSdkVersion>/`
        return androidJarFromSdkComponents().parentFile.name
    }

    private File androidJarFromSdkComponents() {
        AndroidComponentsExtension androidComponents = project.extensions.findByType(AndroidComponentsExtension)
        if (androidComponents == null) {
            throw new GradleException("Cannot locate the Android platform JAR, no Android plugin applied to project '${project.path}'")
        }
        List<RegularFile> bootClasspath = androidComponents.sdkComponents.bootClasspath.get()
        File androidJar = bootClasspath*.asFile.find { File file -> file.name == 'android.jar' }
        if (androidJar == null) {
            throw new GradleException("Cannot locate 'android.jar' in the Android boot classpath: ${bootClasspath*.asFile}")
        }
        return androidJar
    }

    @TaskAction
    @SuppressWarnings("BuilderMethodWithSideEffects")
    protected void createMockableAndroidJar() {
        File outputJar = getOutputJar()
        if (!outputJar.parentFile.mkdirs() && !outputJar.parentFile.isDirectory()) {
            throw new IOException("Could not create directory at ${outputJar.parentFile}")
        }

        if (outputJar.isFile()) {
            outputJar.delete()
        }

        MockableJarGenerator generator = new MockableJarGenerator(returnDefaultValues)
        generator.createMockableJar(inputJar, outputJar)
    }

}
