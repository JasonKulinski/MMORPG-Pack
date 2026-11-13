plugins {
    java
}

group = "dev.jason"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    // Matches the server: Paper 26.3 build 200, WorldEdit 7.4.6
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
