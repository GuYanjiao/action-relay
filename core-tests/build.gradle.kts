import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm") version "2.4.21" }
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.add("-jvm-default=no-compatibility") }
    sourceSets["main"].kotlin.apply {
        srcDir("../android/src")
        include("cn/local/actionrelay/protocol/**", "cn/local/actionrelay/core/**", "cn/local/actionrelay/FrameQueue.kt", "cn/local/actionrelay/GpsLifecycle.kt", "cn/local/actionrelay/ReconnectPolicy.kt", "cn/local/actionrelay/WatchOutbox.kt")
    }
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets["main"].java.setSrcDirs(listOf("../android/src/cn/local/actionrelay/protocol", "../android/src/cn/local/actionrelay/core", "../tests/protocol", "../tests/transport", "../tests/lifecycle"))
val entrypoints = listOf("cn.local.actionrelay.protocol.NativeCoreParityTest", "cn.local.actionrelay.protocol.DjiProtocolSelfTest", "cn.local.actionrelay.protocol.CameraTelemetryTest", "cn.local.actionrelay.protocol.DjiCaptureActionTest", "cn.local.actionrelay.protocol.DjiSessionTransitionTest", "cn.local.actionrelay.protocol.GpsProtocolTest", "FrameQueueTest", "ReconnectPolicyTest", "WatchOutboxTest", "cn.local.actionrelay.GpsLifecycleTest")
val runs = entrypoints.mapIndexed { i, entry -> tasks.register<JavaExec>("regression$i") {
    dependsOn(tasks.classes); classpath = sourceSets["main"].runtimeClasspath; mainClass.set(entry)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.library.path=${rootDir.parentFile}/android/native-libs/host")
    workingDir(rootDir.parentFile)
    if (entry.endsWith("DjiProtocolSelfTest")) args("tests/connect_cmd_frame.txt")
} }
tasks.register("protocolRegression") { dependsOn(runs) }
