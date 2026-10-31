plugins { id("com.android.application") }
android {
 namespace = "com.armanuser.messenger"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.armanuser.messenger.telegram"
  minSdk = 26
  targetSdk = 35
  versionCode = 5
  versionName = "0.5.0-liquid-glass"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
}
dependencies { implementation("io.github.tdlib-android:core:0.1.1") }