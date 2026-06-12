
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.4.1") // Use the latest stable version
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0") // Use the latest stable version
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
