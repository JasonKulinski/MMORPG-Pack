plugins {
    java
}

group = "dev.jason"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Matches the server: Paper 26.3 build 200
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")
    // RPGCore and RPGTowns (build them first: their jars in ../<plugin>/build/libs)
    compileOnly(fileTree("../RPGCore/build/libs") { include("*.jar") })
    compileOnly(fileTree("../RPGTowns/build/libs") { include("*.jar") })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
