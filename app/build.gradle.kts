plugins { id("com.android.application") }
android {
  namespace = "com.armanuser.messenger"
  compileSdk = 35
  defaultConfig {
    applicationId = "com.armanuser.messenger"
    minSdk = 26
    targetSdk = 32
    versionCode = 3
    versionName = "0.3.0-demo"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
