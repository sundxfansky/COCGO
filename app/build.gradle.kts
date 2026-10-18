import groovy.json.JsonSlurper
import java.util.Properties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.coc.suncode"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use {
            localProperties.load(it)
        }
    }
    val baseUrl: String = localProperties.getProperty("BASE_URL") ?: ""
    fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    // Recover the legacy packaging key without keeping the unused C++ library in APKs.
    val legacySource = file("src/main/cpp/security/keys.cpp").readText()
    fun keyPart(name: String): List<Int> {
        val body = Regex("$name\\[32]\\s*=\\s*\\{([^}]+)}").find(legacySource)?.groupValues?.get(1)
            ?: error("Missing legacy key component")
        return Regex("0x[0-9a-fA-F]+").findAll(body).map { it.value.substring(2).toInt(16) }.toList().also { require(it.size == 32) }
    }
    val legacyKey = keyPart("KEY_MASK").zip(keyPart("OBFUSCATED_KEY")).joinToString("") { (a,b) -> "%02x".format(a xor b) }
    val updatePublicKey = localProperties.getProperty("HOT_UPDATE_PUBLIC_KEY") ?: System.getenv("HOT_UPDATE_PUBLIC_KEY").orEmpty()
    val requestedAbis = providers.gradleProperty("abis").orNull?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    require(requestedAbis.all { it in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })

    defaultConfig {
        applicationId = "com.coc.suncode"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_URL", quoted(baseUrl))
        buildConfigField("String", "LEGACY_JAR_KEY", quoted(legacyKey))
        buildConfigField("String", "HOT_UPDATE_PUBLIC_KEY", quoted(updatePublicKey))
        ndk { abiFilters += requestedAbis }
    }

    buildTypes {
        release {
            manifestPlaceholders["debugReloadEnabled"] = "false"
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            manifestPlaceholders["debugReloadEnabled"] = "true"
            isDebuggable = true
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Keep the historical C++ sources in the repository without shipping their unused library.

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
// A single catalog determines ownership for the compiler outputs and the runtime loader.
@Suppress("UNCHECKED_CAST")
val featureSpecs = JsonSlurper().parse(file("src/main/assets/feature-plugins.json")) as List<Map<String, Any>>
fun owns(spec: Map<String, Any>, classFile: String): Boolean {
    val name = classFile.removeSuffix(".class").replace('/', '.')
    @Suppress("UNCHECKED_CAST")
    return (spec["prefixes"] as List<String>).any {
        if (it.endsWith('.')) name.startsWith(it) else name == it || name.startsWith(it + "$" )
    }
}

val validateFeatureBoundaries = tasks.register<Exec>("validateFeatureBoundaries") {
    group = "verification"
    dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac")
    commandLine(
        providers.gradleProperty("python").orElse("python3").get(),
        rootProject.file("scripts/verify_feature_boundaries.py").path,
        "--catalog", file("src/main/assets/feature-plugins.json").path,
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").get().asFile.path,
        layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile.path
    )
}

fun registerPluginBuild(artifactId: String, feature: Map<String, Any>?) = tasks.register(
    if (feature == null) "buildJar" else "buildFeature" + artifactId.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
) {
    group = "build"
    description = "Build the $artifactId dynamic plugin"
    dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac", validateFeatureBoundaries)
    val output = layout.buildDirectory.file(if (feature == null) "plugin/code.jar" else "plugin/features/$artifactId.jar")
    outputs.file(output)
    inputs.files(provider { fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) })
    inputs.files(configurations.named("debugRuntimeClasspath"))
    inputs.file("src/main/assets/feature-plugins.json")
    val featureVersion = providers.gradleProperty("featureVersion").orElse("1")
    inputs.property("featureVersion", featureVersion)
    doLast {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val buildTools = File(sdk, "build-tools/${android.buildToolsVersion}")
        val d8 = File(buildTools, if (System.getProperty("os.name").startsWith("Windows")) "d8.bat" else "d8")
        require(d8.isFile) { "D8 not installed: $d8" }
        val classRoots = listOf("tmp/kotlin-classes/debug", "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes", "intermediates/javac/debug/compileDebugJavaWithJavac/classes").map { layout.buildDirectory.dir(it).get().asFile }
        val allClasses = linkedMapOf<String, File>()
        classRoots.filter { it.exists() }.forEach { dir -> fileTree(dir) { include("**/*.class") }.forEach {
            allClasses.putIfAbsent(it.relativeTo(dir).invariantSeparatorsPath, it)
        } }
        val classes = allClasses.filter { (name, _) ->
            name.startsWith("com/coc/suncode/jar/") &&
                (if (feature == null) featureSpecs.none { owns(it, name) } else owns(feature, name))
        }.values.toList()
        check(classes.isNotEmpty()) { "No plugin classes were compiled" }
        val stage = layout.buildDirectory.dir("plugin/staging-$artifactId").get().asFile.apply { deleteRecursively(); mkdirs() }
        val arguments = mutableListOf("--release", "--min-api", "24", "--lib", File(sdk, "platforms/android-36/android.jar").path, "--output", File(stage, "code.jar").path)
        configurations.getByName("debugRuntimeClasspath").files.forEachIndexed { index, dependency ->
            if (dependency.extension == "jar") arguments.addAll(listOf("--classpath", dependency.path))
            if (dependency.extension == "aar") ZipFile(dependency).use { archive ->
                archive.entries().asSequence().filter { it.name.endsWith(".jar") }.forEachIndexed { nested, entry ->
                    val extracted = File(stage, "dependency-$index-$nested.jar")
                    archive.getInputStream(entry).use { input -> extracted.outputStream().use { input.copyTo(it) } }
                    arguments.addAll(listOf("--classpath", extracted.path))
                }
            }
        }
        val hostJar = File(stage, "host.jar")
        JarOutputStream(hostJar.outputStream()).use { jar ->
            allClasses.filterValues { it !in classes }.forEach { (entry, source) ->
                jar.putNextEntry(JarEntry(entry)); source.inputStream().use { it.copyTo(jar) }; jar.closeEntry()
            }
        }
        arguments.addAll(listOf("--classpath", hostJar.path))
        arguments.addAll(classes.map { it.path })
        val flags = File(stage, "d8.args")
        // D8 reads one literal argument per line; shell quoting would become part of the path.
        flags.writeText(arguments.joinToString("\n"))
        val log = File(stage, "d8.log")
        val exit = ProcessBuilder(d8.path, "@${flags.path}").redirectErrorStream(true).redirectOutput(log).start().waitFor()
        check(exit == 0) { "D8 failed; previous plugin remains intact:\n${log.readText().takeLast(12000)}" }
        val target = output.get().asFile.apply { parentFile.mkdirs() }
        val packaged = File(stage, "packaged.jar")
        JarOutputStream(packaged.outputStream()).use { jar ->
            ZipFile(File(stage, "code.jar")).use { dex ->
                dex.entries().asSequence().forEach { entry ->
                    jar.putNextEntry(JarEntry(entry.name)); dex.getInputStream(entry).use { it.copyTo(jar) }; jar.closeEntry()
                }
            }
            if (feature != null) {
                require(featureVersion.get().toLong() > 0)
                jar.putNextEntry(JarEntry("feature.properties"))
                jar.write("id=$artifactId\nversion=${featureVersion.get()}\napi=1\n".toByteArray())
                jar.closeEntry()
            } else {
                // A core release carries compatible fallbacks without defining their classes itself.
                featureSpecs.forEach { spec ->
                    val id = spec["id"] as String
                    jar.putNextEntry(JarEntry("features/$id.jar"))
                    layout.buildDirectory.file("plugin/features/$id.jar").get().asFile.inputStream().use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }
        Files.move(packaged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
val featureBuilds = featureSpecs.associate { it["id"] as String to registerPluginBuild(it["id"] as String, it) }
val buildJarTask = registerPluginBuild("core", null)
buildJarTask.configure {
    dependsOn(featureBuilds.values)
    inputs.files(featureBuilds.keys.map { layout.buildDirectory.file("plugin/features/$it.jar") })
}
tasks.register("buildFeaturePlugins") {
    group = "build"
    val selected = providers.gradleProperty("feature").orNull
    require(selected == null || selected in featureBuilds) { "Unknown feature: $selected" }
    dependsOn(if (selected == null) featureBuilds.values else listOf(featureBuilds.getValue(selected)))
}

tasks.register<Exec>("signFeaturePlugin") {
    group = "build"
    val selected = providers.gradleProperty("feature")
    dependsOn("buildFeaturePlugins")
    doFirst {
        val id = selected.orNull ?: error("Pass -Pfeature=main_attack")
        require(id in featureBuilds)
        val key = providers.gradleProperty("signingKey").orNull ?: error("Pass -PsigningKey=/private/key.pem")
        commandLine(providers.gradleProperty("python").orElse("python3").get(),
            rootProject.file("scripts/sign_feature_plugin.py").path,
            "--input", layout.buildDirectory.file("plugin/features/$id.jar").get().asFile.path, "--key", key)
    }
}

// Device tests exercise the real split dex artifacts, not only the APK's bundled classes.
val prepareFeatureTestAssets = tasks.register<Copy>("prepareFeatureTestAssets") {
    dependsOn(buildJarTask)
    from(layout.buildDirectory.file("plugin/code.jar"))
    into(layout.buildDirectory.dir("generated/featureTestAssets"))
}
android.sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/featureTestAssets").get().asFile)
tasks.configureEach {
    if (name == "mergeDebugAndroidTestAssets") dependsOn(prepareFeatureTestAssets)
}

tasks.register("validateUploadJarGuard") {
    group = "verification"
    description = "Block uploadJar when MainScript test code is still enabled"

    doLast {
        val mainScriptFile = file("src/main/java/com/coc/suncode/jar/code/MainScript.kt")
        if (!mainScriptFile.exists()) {
            throw GradleException("Cannot validate upload safety because MainScript.kt was not found.")
        }

        val scriptLines = mainScriptFile.readLines()
        val runMainScriptStart = scriptLines.indexOfFirst { it.contains("suspend fun runMainScript()") }
        val runTestCodeDeclaration = scriptLines.indexOfFirst { it.contains("private suspend fun runTestCode()") }
        if (runMainScriptStart == -1 || runTestCodeDeclaration == -1 || runTestCodeDeclaration <= runMainScriptStart) {
            throw GradleException("Cannot validate upload safety because the expected MainScript structure was not found.")
        }

        // Only scan the main script body so the helper declaration itself does not trigger the guard.
        val runMainScriptLines = scriptLines.subList(runMainScriptStart, runTestCodeDeclaration)
        val activeRunTestCodeLine = Regex("""^\s*runTestCode\s*\(\s*\)\s*(?://.*)?$""")
        val isTestHookEnabled = runMainScriptLines.any { line ->
            activeRunTestCodeLine.matches(line)
        }

        if (isTestHookEnabled) {
            throw GradleException(
                "runTestCode() is still enabled in MainScript.kt. Comment out or disable that call before running uploadJar."
            )
        }
    }
}

tasks.named("buildJar") {
    mustRunAfter("validateUploadJarGuard")
}

// Signing and upload configuration is explicit; private keys are never stored in the repository.
tasks.register("uploadJar") {
    group = "build"
    dependsOn("validateUploadJarGuard", "buildJar")
    doLast {
        val key = providers.gradleProperty("signingKey").orNull ?: error("Pass -PsigningKey=/private/key.pem")
        val url = providers.gradleProperty("uploadUrl").orNull ?: error("Pass -PuploadUrl=https://host/api/v1")
        val python = providers.gradleProperty("python").orElse("python3").get()
        val command = listOf(python, rootProject.file("scripts/release_plugin.py").path, "--input", layout.buildDirectory.file("plugin/code.jar").get().asFile.path, "--key", key, "--url", url)
        check(ProcessBuilder(command).inheritIO().start().waitFor() == 0) { "Plugin publication failed" }
    }
}

tasks.register("deployAndReload") {
    group = "custom"
    dependsOn("buildJar")
    doLast {
        val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
        val adb = File(sdk, "platform-tools/" + if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb").path
        val prefix = mutableListOf(adb)
        providers.gradleProperty("device").orNull?.let { prefix.addAll(listOf("-s", it)) }
        fun run(vararg args: String) { check(ProcessBuilder(prefix + args).inheritIO().start().waitFor() == 0) { "adb failed: ${args.firstOrNull()}" } }
        // Use the raw Unix timestamp (not an offset from it) so this always outranks any
        // leftover plugin file on the device, no matter how large its version number is.
        val version = System.currentTimeMillis() / 1000
        val name = "$version.jar"
        val temporary = "/data/local/tmp/sun-plugin-$version.jar"
        // Only this task's exact temporary artifact is removed, even on failure.
        try {
            run("push", layout.buildDirectory.file("plugin/code.jar").get().asFile.path, temporary)
            // adb joins remote arguments, so quote the whole root script for the device shell.
            val rootScript = "mkdir -p /data/data/com.coc.suncode/files/assets && cp $temporary /data/data/com.coc.suncode/files/assets/$name && chmod 444 /data/data/com.coc.suncode/files/assets/$name"
            run("shell", "su", "-c", "'" + rootScript.replace("'", "'\\''") + "'")
            run("shell", "am", "broadcast", "-a", "com.coc.suncode.SUN_RELOAD", "-n", "com.coc.suncode/.core.system.daemon.DebugReloadReceiver")
        } finally { run("shell", "rm", "-f", temporary) }
    }
}

tasks.register<Exec>("rustBuild") {
    group = "build"
    description = "Build Rust logic using cargo-ndk"
    workingDir = file("../rust_logic")

    // Android Studio launches Gradle with its own process environment, which does
    // not source ~/.zshrc, so cargo/ANDROID_NDK_HOME must be resolved here instead
    // of relying on an interactive shell having set them up first.
    val home = System.getProperty("user.home")

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkRoot = localProps.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: "$home/Library/Android/sdk"

    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: file("$sdkRoot/ndk").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
            ?.absolutePath
        ?: throw GradleException("No Android NDK found under $sdkRoot/ndk. Install one via the SDK Manager.")

    // rustup is a Homebrew keg-only formula, so its cargo/rustc shims are never
    // symlinked onto the default PATH; resolve them directly instead.
    val cargoExecutable = listOf(
        "/opt/homebrew/opt/rustup/bin/cargo",
        "/usr/local/opt/rustup/bin/cargo",
        "$home/.cargo/bin/cargo"
    ).firstOrNull { file(it).exists() } ?: "cargo"

    environment("ANDROID_NDK_HOME", ndkHome)
    environment(
        "PATH",
        listOfNotNull(
            file(cargoExecutable).parentFile?.absolutePath,
            "$home/.cargo/bin",
            System.getenv("PATH")
        ).joinToString(":")
    )

    val abis = providers.gradleProperty("abis").orNull?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    val rustOutput = layout.buildDirectory.dir("rustJni").get().asFile
    inputs.files(fileTree("../rust_logic/src"), file("../rust_logic/Cargo.toml"), file("../rust_logic/Cargo.lock"))
    inputs.property("abis", abis)
    inputs.property("ndk", ndkHome)
    inputs.property("cargo", cargoExecutable)
    outputs.dir(rustOutput)
    doFirst { rustOutput.deleteRecursively(); rustOutput.mkdirs() }
    commandLine(listOf(cargoExecutable, "ndk") + abis.flatMap { listOf("-t", it) } + listOf("-o", rustOutput.path, "build", "--release", "--locked"))
}

android.sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(layout.buildDirectory.dir("rustJni")))

tasks.configureEach {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("rustBuild")
    }
}

// Small device helpers keep the normal debug loop reproducible and avoid copying
// shell commands from the documentation by hand.
tasks.register<Exec>("runDebugOnDevice") {
    group = "debug"
    description = "Install the debug APK and launch MainActivity on the selected adb device"
    dependsOn("assembleDebug")
    val serial = providers.gradleProperty("device").orNull
    val installCommand = buildList {
        add("adb")
        if (!serial.isNullOrBlank()) {
            add("-s")
            add(serial)
        }
        addAll(listOf("install", "-r", layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath))
    }
    commandLine(installCommand)
    doLast {
        val stop = mutableListOf("adb")
        if (!serial.isNullOrBlank()) stop.addAll(listOf("-s", serial))
        stop.addAll(listOf("shell", "am", "force-stop", "com.coc.suncode"))
        ProcessBuilder(stop).inheritIO().start().waitFor()

        val start = mutableListOf("adb")
        if (!serial.isNullOrBlank()) start.addAll(listOf("-s", serial))
        start.addAll(listOf("shell", "am", "start", "-W", "-n", "com.coc.suncode/.MainActivity"))
        ProcessBuilder(start).inheritIO().start().waitFor()
    }
}

tasks.register<Exec>("debugLogs") {
    group = "debug"
    description = "Show application and native runtime logs"
    val serial = providers.gradleProperty("device").orNull
    commandLine(buildList {
        add("adb")
        if (!serial.isNullOrBlank()) addAll(listOf("-s", serial))
        addAll(listOf("logcat", "sun_debug:V", "sun_rust:V", "AndroidRuntime:E", "*:S"))
    })
}

dependencies {

    implementation(libs.libsu.core)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.timber)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.onnx.runtime)
    implementation(libs.kotlin.reflect)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
//    debugImplementation(libs.leakcanary.android)
}
