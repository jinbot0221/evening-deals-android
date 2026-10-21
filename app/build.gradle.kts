import java.util.Properties
import groovy.json.JsonSlurper

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
val firebaseConfig = Properties().apply {
 val file = rootProject.file("firebase.local.properties")
 if (file.exists()) file.inputStream().use { load(it) }
}
fun firebaseValue(name: String, fallback: String = "") = providers.gradleProperty(name).orNull ?: firebaseConfig.getProperty(name, fallback)
val googleConfigFile = file("google-services.json")
val googleConfig = if (googleConfigFile.exists()) JsonSlurper().parse(googleConfigFile) as Map<*, *> else emptyMap<Any, Any>()
val googleProject = googleConfig["project_info"] as? Map<*, *>
val googleClient = (googleConfig["client"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull {
 val info = it["client_info"] as? Map<*, *>
 (info?.get("android_client_info") as? Map<*, *>)?.get("package_name") == "com.portfolio.eveningdeals"
}
if (googleConfigFile.exists()) require(googleClient != null) { "google-services.json에 com.portfolio.eveningdeals Android 앱이 없습니다." }
val googleClientInfo = googleClient?.get("client_info") as? Map<*, *>
val googleApiKey = ((googleClient?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key") as? String
val configuredFirebase = googleConfigFile.exists() || firebaseConfig.containsKey("firebaseProjectId") || providers.gradleProperty("firebaseProjectId").isPresent
val emulatorSetting = firebaseValue("firebaseEmulators", if(configuredFirebase) "false" else "true")
require(emulatorSetting in listOf("true", "false")) { "firebaseEmulators must be true or false" }
val useFirebaseEmulators = emulatorSetting == "true"
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
 namespace = "com.portfolio.eveningdeals"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.portfolio.eveningdeals"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "0.2.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  buildConfigField("String", "BACKEND", quoted(firebaseValue("backend", "firebase")))
  buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(if(useFirebaseEmulators) "demo-evening-deals" else firebaseValue("firebaseProjectId", googleProject?.get("project_id") as? String ?: "demo-evening-deals")))
  buildConfigField("String", "FIREBASE_APP_ID", quoted(if(useFirebaseEmulators) "1:1234567890:android:0123456789abcdef" else firebaseValue("firebaseAppId", googleClientInfo?.get("mobilesdk_app_id") as? String ?: "1:1234567890:android:0123456789abcdef")))
  buildConfigField("String", "FIREBASE_API_KEY", quoted(if(useFirebaseEmulators) "demo-api-key" else firebaseValue("firebaseApiKey", googleApiKey ?: "demo-api-key")))
  buildConfigField("String", "FIREBASE_EMULATOR_HOST", quoted(firebaseValue("firebaseEmulatorHost", "10.0.2.2")))
  buildConfigField("String", "API_URL", "\"" + (providers.gradleProperty("apiUrl").orNull ?: "http://10.0.2.2:8080") + "\"")
 }
 buildTypes {
  debug { buildConfigField("boolean", "FIREBASE_EMULATORS", emulatorSetting) }
  release { buildConfigField("boolean", "FIREBASE_EMULATORS", "false") }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
 implementation("com.google.firebase:firebase-auth")
 implementation("com.google.firebase:firebase-firestore")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("com.google.zxing:core:3.5.3")
 debugImplementation("androidx.compose.ui:ui-tooling")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:core:1.6.1")
}
