import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application"); kotlin("plugin.compose") }

val manifestText = file("AndroidManifest.xml").readText()
fun manifestVersion(key: String) = Regex("android:$key=\"([^\"]+)\"").find(manifestText)!!.groupValues[1]
android {
    namespace = "cn.local.actionrelay"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "cn.local.actionrelay"
        minSdk = 31; targetSdk = 36
        versionCode = manifestVersion("versionCode").toInt()
        versionName = manifestVersion("versionName")
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("src")); res.setSrcDirs(listOf("res"))
        jniLibs.setSrcDirs(listOf("native-libs"))
        assets.setSrcDirs(listOf("native-assets"))
    }
    signingConfigs {
        create("fixedRelease") {
            storeFile = System.getenv("ACTION_RELAY_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("ACTION_RELAY_STORE_PASSWORD")
            keyPassword = System.getenv("ACTION_RELAY_KEY_PASSWORD")
            keyAlias = "guyanjiao-release"; storeType = "PKCS12"
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs["fixedRelease"]; isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = false }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(files("libs/xms-wearable-1.4.jar"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
}
