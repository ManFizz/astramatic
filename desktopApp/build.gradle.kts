import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.material3)

    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.partialContent)
    implementation(libs.ktor.server.autoHead)
    implementation(libs.zxing.core)
}

compose.desktop {
    application {
        mainClass = "com.manfis.astramatic.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "AstraMatic"
            packageVersion = "1.0.0"
        }
    }
}