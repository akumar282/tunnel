plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.2"
}

group = "com.spin"
version = "1.0.0"

repositories {
    mavenCentral()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("tunnel.jar")
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