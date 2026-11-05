plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("com.pilot.mobile.bridge.MainKt") }
val irohVersion = providers.gradleProperty("pilot.irohVersion").get()
dependencies {
    implementation(project(":transport"))
    implementation("computer.iroh:iroh:$irohVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
