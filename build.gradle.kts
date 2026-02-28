plugins {
    kotlin("jvm") version "2.3.0"
}

group = "com.spin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("ch.qos.logback:logback-classic:1.5.32")
}

kotlin {
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
}