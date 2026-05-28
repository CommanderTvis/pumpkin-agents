import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "io.github.commandertvis.pumpkins"
version = "0.1.0"

configurations.all {
    // plugin-api ships kotlin-stdlib/reflect 1.6/1.8 mixed; force everything to 1.9.23.
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-common:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}"
        )
    }
}

dependencies {
    compileOnly(libs.spigot.api)
    compileOnly(libs.pluginApi.common)
    compileOnly(libs.pluginApi.command)
    compileOnly(libs.pluginApi.chat)
    compileOnly(libs.pluginApi.coroutines)
    // HolographicDisplays — API only at compile time; the plugin jar is dropped into run/plugins by Gradle.
    // softdepend in plugin.yml lets us start without it (HUD becomes a no-op).
    compileOnly(libs.holographicdisplays.api)

    // 2p-kt: pure-Kotlin Prolog. We exclude kotlin transitives so the plugin-api runtime jar stays
    // the single source of kotlin-stdlib at runtime (avoids LinkageError on kotlin.jvm.functions.*).
    implementation(libs.tuprolog.solve.classic) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.tuprolog.dsl.theory) {
        exclude(group = "org.jetbrains.kotlin")
    }

    testImplementation(libs.kotest.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.junit.jupiter)
    // Tests run in a plain JVM; production gets snakeyaml from spigot-api at runtime
    // (we mark spigot compileOnly), so we need to add it ourselves for the test classpath.
    testImplementation(libs.snakeyaml)
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

kotlin.compilerOptions {
    jvmTarget = JvmTarget.JVM_17
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xskip-metadata-version-check")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    // IDEA's resource analyzer chokes on `filesMatching { expand(...) }`
    // ("Cannot resolve resource filtering of MatchingCopyAction"). A plain `filter` lambda
    // produces the same output and IDEA leaves it alone.
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    filesMatching("plugin.yml") {
        filter { line -> line.replace($$"${version}", versionString) }
    }
}

// kotlin.enums.EnumEntries* (Kotlin 1.9 `.entries` support) is missing from
// plugin-api's bundled 1.8.0 stdlib. We do NOT want to bundle the whole stdlib
// (LinkageError on kotlin.Function from dual-classloader load), but we can ship
// JUST the kotlin.enums package — there's no overlap with the 1.8.0 stdlib.
val kotlinEnumsShim by configurations.creating
dependencies {
    kotlinEnumsShim("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

tasks.shadowJar {
    archiveBaseName.set("pumpkin-agents")
    archiveClassifier.set("all")
    // Pull in the kotlin.enums classes (Kotlin 1.9 `.entries` support) absent from plugin-api's 1.8.0 stdlib.
    from(kotlinEnumsShim.map { zipTree(it) }) {
        include("kotlin/enums/**")
    }
    // snakeyaml: not bundled — spigot-api ships it at runtime, so we ride that copy.
    // 2p-kt — bundle but don't relocate (its packages don't collide with anything).
    // Relocating would also need rewriting reflective lookups inside the solver, which is brittle.
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
