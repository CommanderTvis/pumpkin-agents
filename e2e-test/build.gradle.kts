import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.commandertvis.pumpkins"
version = "0.1.0"

dependencies {
    testImplementation(libs.kotest.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.snakeyaml)
    testImplementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

kotlin.compilerOptions {
    jvmTarget = JvmTarget.JVM_17
}

evaluationDependsOn(":plugin")

val paperVer = libs.versions.paperVersion.get()
val paperBld = libs.versions.paperBuild.get()
val pluginApiVer = libs.versions.pluginApi.get()
val runDir = rootProject.layout.projectDirectory.dir("run")
val pluginShadowJar = project(":plugin").tasks.named("shadowJar")

tasks.test {
    description = "Runs the e2e suite against a real Paper instance provisioned by Gradle"
    useJUnitPlatform()

    dependsOn(
        pluginShadowJar,
        rootProject.tasks.named("downloadPaper"),
        rootProject.tasks.named("downloadPluginApiRuntime")
    )

    systemProperty("pumpkin.paperJar", runDir.file("paper-$paperVer-$paperBld.jar").asFile.absolutePath)
    systemProperty(
        "pumpkin.pluginApiRuntimeJar",
        runDir.dir("plugins").file("runtime-$pluginApiVer.jar").asFile.absolutePath
    )
    systemProperty(
        "pumpkin.pluginJar",
        pluginShadowJar.flatMap { (it as org.gradle.jvm.tasks.Jar).archiveFile }.get().asFile.absolutePath
    )
    systemProperty("pumpkin.mapsDir", rootProject.layout.projectDirectory.dir("maps").asFile.absolutePath)
    systemProperty("pumpkin.serverDir", layout.buildDirectory.dir("e2e-server").get().asFile.absolutePath)
}
