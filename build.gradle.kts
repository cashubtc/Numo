buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.10")
    }
}

plugins {
    id("com.android.application") version "8.11.2" apply false
    id("com.android.library") version "8.11.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
}
