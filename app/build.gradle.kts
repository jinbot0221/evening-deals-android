plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "kr.evening.deals"
 compileSdk = 35
 defaultConfig {
  applicationId = "kr.evening.deals"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "0.1.0"
  buildConfigField("String", "API_URL", "\"" + (providers.gradleProperty("apiUrl").orNull ?: "http://10.0.2.2:8080") + "\"")
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
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("com.google.zxing:core:3.5.3")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
