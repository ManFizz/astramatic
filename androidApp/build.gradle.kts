import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.roborazzi)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))
    // Cardboard SDK одним файлом; пересборка из исходников описана в settings.gradle.kts.
    // Зависимости .aar не подтягиваются автоматически, поэтому перечислены ниже.
    implementation(files("libs/cardboard-sdk.aar"))
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.playServices.vision)
    implementation(libs.protobuf.javalite)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.smbj)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.coil.video)
    implementation(libs.playServices.codeScanner)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.uiTestJunit4)
    debugImplementation(libs.compose.uiTestManifest)
}

// Версия из git: номер сборки — число коммитов, плюс короткий хеш; «*» — есть незакоммиченные правки.
// По ним на главной видно, та ли версия стоит на телефоне.
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val commitHash = git("rev-parse", "--short", "HEAD") ?: "unknown"
val commitDirty = git("status", "--porcelain") != null

android {
    namespace = "com.manfis.astramatic"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.manfis.astramatic"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "1.0.$commitCount"
        buildConfigField("String", "COMMIT", "\"$commitHash${if (commitDirty) "*" else ""}\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Ключ подписи release — вне репозитория. Нет файла — release соберётся без подписи, debug не затронут.
    val releaseKey = File(System.getProperty("user.home"), ".android-keys/astramatic.properties")
    signingConfigs {
        if (releaseKey.exists()) {
            create("release") {
                val props = Properties().apply { releaseKey.inputStream().use(::load) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // Отладочная версия ставится рядом с release, со своими настройками.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "AstraMatic Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["appLabel"] = "AstraMatic"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    testOptions {
        unitTests {
            // Robolectric берёт ресурсы и манифест настоящего приложения.
            isIncludeAndroidResources = true
            all {
                // Robolectric создаёт дескрипторы файлов через внутренний API JDK.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}