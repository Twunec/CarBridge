import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.carbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.carbridge"
        minSdk = 35
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release key lives outside the repo; set CARBRIDGE_* in ~/.gradle/gradle.properties
    val releaseStore = providers.gradleProperty("CARBRIDGE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("CARBRIDGE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("CARBRIDGE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("CARBRIDGE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        // Installs next to the release build, which is signed with a different key
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key (e.g. a fresh clone), fall back to debug signing for local tests
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    // The parked-only lock's logic is unit tested with the app's tests
    sourceSets["test"].java.srcDirs("../guard/src/main/java", "../guard/src/test/java")
}

/**
 * The parked-only lock that CarBridge adds to every car copy: plain Java against the framework
 * only, compiled and dexed separately, then shipped as an asset and injected at patch time.
 */
val compileParkedGuard = tasks.register<JavaCompile>("compileParkedGuard") {
    source = fileTree("../guard/src/main/java")
    classpath = files()
    options.bootstrapClasspath = files(androidComponents.sdkComponents.bootClasspath)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
    destinationDirectory.set(layout.buildDirectory.dir("guard/classes"))
}

abstract class DexParkedGuard : DefaultTask() {
    @get:InputFiles abstract val classes: ConfigurableFileCollection
    @get:InputFiles abstract val androidJar: ConfigurableFileCollection
    @get:InputFile abstract val d8Jar: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun dex() {
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        val classFiles = classes.asFileTree.matching { include("**/*.class") }.files.map { it.path }
        exec.javaexec {
            classpath(d8Jar)
            mainClass.set("com.android.tools.r8.D8")
            args(listOf("--release", "--min-api", "26", "--output", work.path))
            androidJar.forEach { args("--lib", it.path) }
            args(classFiles)
        }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        File(work, "classes.dex").copyTo(File(out, "carbridge_guard.dex"), overwrite = true)
    }
}

val dexParkedGuard = tasks.register<DexParkedGuard>("dexParkedGuard") {
    classes.from(compileParkedGuard.flatMap { it.destinationDirectory })
    androidJar.from(androidComponents.sdkComponents.bootClasspath)
    d8Jar.set(
        androidComponents.sdkComponents.sdkDirectory.map { sdk -> sdk.file("build-tools/${android.buildToolsVersion}/lib/d8.jar") },
    )
    outputDir.set(layout.buildDirectory.dir("generated/guard-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(dexParkedGuard, DexParkedGuard::outputDir)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    implementation("com.android.tools.build:apksig:9.5.1")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // Optional real-APK fixture: ./gradlew :patcher:testDebugUnitTest -PpatchFixture=/path/app.apk
    project.findProperty("patchFixture")?.let { systemProperty("patchFixture", it) }
    // Mirror a phone's heap cap to catch out-of-memory patches: -PpatchHeap=256m
    project.findProperty("patchHeap")?.let { maxHeapSize = it.toString() }
    systemProperty("patchOut", layout.buildDirectory.file("patch-test/out.apk").get().asFile.path)
    // The fixture test injects the real parked-only lock
    dependsOn(dexParkedGuard)
    systemProperty("guardDex", layout.buildDirectory.file("generated/guard-assets/carbridge_guard.dex").get().asFile.path)
}
