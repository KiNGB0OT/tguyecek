plugins { id("com.android.application") }
android {
 namespace = "com.armanuser.messenger"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.armanuser.messenger.telegram"
  minSdk = 26
  targetSdk = 35
  versionCode = 4
  versionName = "0.4.0-telegram-beta"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
}
dependencies { implementation("io.github.tdlib-android:core:0.1.1") }