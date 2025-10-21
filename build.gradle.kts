plugins {
    java
}

group = "com.kakamine.minedisaster"
version = "1.0.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}
