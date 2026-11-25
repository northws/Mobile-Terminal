plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val termux = file(System.getenv("WARP_TERMUX_DIR") ?: "../../.upstream/termux")
val tawc = file(System.getenv("WARP_TAWC_DIR") ?: "../../.upstream/tawc")
val revisions = rootProject.file("../upstream/revisions.env").readLines().associate { line ->
    line.substringBefore('=') to line.substringAfter('=')
}
val debian = revisions.getValue("DEBIAN_REV")
val debianUrl = "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/$debian/trixie/slim/oci/blobs/rootfs.tar.gz"

android {
    namespace = "dev.warpterminal.mobile"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.warpterminal.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "0.2.11"
        ndk.abiFilters.add("arm64-v8a")
        buildConfigField("String", "DEBIAN_URL", "\"$debianUrl\"")
        buildConfigField("String", "DEBIAN_SHA256", "\"${revisions.getValue("DEBIAN_SHA256")}\"")
        buildConfigField("long", "DEBIAN_BYTES", "${revisions.getValue("DEBIAN_BYTES")}L")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.named("main") {
        java.srcDir(termux.resolve("terminal-emulator/src/main/java"))
        java.srcDir(layout.buildDirectory.dir("generated/terminal-renderer"))
        java.srcDir(layout.buildDirectory.dir("generated/tawc-input"))
        System.getenv("WARP_ANDROID_ASSETS")?.let { assets.srcDir(it) }
        System.getenv("WARP_ANDROID_LIBS")?.let { jniLibs.srcDir(it) }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols.add("**/libtawcroot.so")
    }
}

val stageTerminalRenderer by tasks.registering(Copy::class) {
    from(termux.resolve("terminal-view/src/main/java")) { include("**/TerminalRenderer.java") }
    into(layout.buildDirectory.dir("generated/terminal-renderer"))
}
val stageTawcInput by tasks.registering(Copy::class) {
    from(tawc.resolve("app/src/main/java")) {
        include("**/compositor/TawcInputConnection.kt", "**/compositor/ImeOutput.kt")
    }
    into(layout.buildDirectory.dir("generated/tawc-input"))
}
tasks.named("preBuild") { dependsOn(stageTerminalRenderer, stageTawcInput) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
