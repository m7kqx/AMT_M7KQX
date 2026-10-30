import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val properties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { properties.load(it) }
}

android {
    namespace = "com.example.androidmorsetrainer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "uk.co.m7kqx.cwbootcamp"
        minSdk = 24
        targetSdk = 37
        versionCode = 14
        versionName = "V1.1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val isCi = System.getenv("SIGNING_STORE_PASSWORD") != null

            if (isCi) {
                storeFile = file("keystore.jks")
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "Ca512ssie!"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "Ca512ssie!"
            } else {
                properties.getProperty("storeFile")?.let { storeFilePath ->
                    val fileInRoot = rootProject.file(storeFilePath)
                    val fileInApp = file(storeFilePath)
                    storeFile = if (fileInRoot.exists() && !fileInApp.exists()) fileInRoot else fileInApp
                }
                storePassword = properties.getProperty("storePassword") ?: "Ca512ssie!"
                keyAlias = properties.getProperty("keyAlias") ?: "upload"
                keyPassword = properties.getProperty("keyPassword") ?: "Ca512ssie!"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
val currentGitBranch = providers.environmentVariable("GITHUB_REF_NAME").orNull
    ?: runCatching {
        val headFile = rootProject.file(".git/HEAD")
        if (headFile.exists()) {
            val content = headFile.readText().trim()
            if (content.startsWith("ref: refs/heads/")) {
                content.removePrefix("ref: refs/heads/").trim()
            } else {
                content
            }
        } else {
            "main"
        }
    }.getOrNull()
    ?: "main"

val branchSuffix = when {
    currentGitBranch in listOf("main", "master") -> "Stable"
    currentGitBranch == "develop" -> "Dev"
    currentGitBranch.isNotBlank() && currentGitBranch != "HEAD" -> currentGitBranch.replace(Regex("[^a-zA-Z0-9.-]"), "-")
    else -> "Stable"
}

base {
    archivesName.set("CW-Boot-Camp-${android.defaultConfig.versionName}-$branchSuffix")
}

abstract class CopyReleaseAabTask : DefaultTask() {
    @get:InputDirectory
    abstract val bundleDir: DirectoryProperty

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val stable: Property<Boolean>

    @TaskAction
    fun copy() {
        if (!stable.get()) return
        val vName = versionName.get()
        val dir = bundleDir.get().asFile
        val generatedBundle = dir.listFiles { _, name ->
            name.startsWith("CW-Boot-Camp-$vName-Stable") && name.endsWith(".aab")
        }?.firstOrNull { it.name != "CW-Boot-Camp-$vName-Stable.aab" }

        if (generatedBundle != null) {
            val targetFile = File(dir, "CW-Boot-Camp-$vName-Stable.aab")
            generatedBundle.copyTo(targetFile, overwrite = true)
        }
    }
}

val copyReleaseAab = tasks.register<CopyReleaseAabTask>("copyReleaseAab") {
    bundleDir.set(layout.buildDirectory.dir("outputs/bundle/release"))
    versionName.set(android.defaultConfig.versionName ?: "")
    stable.set(branchSuffix == "Stable")
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(copyReleaseAab)
}