plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.badbird"
version = "1.0.3"

repositories {
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "sonatype"
        url = uri("https://oss.sonatype.org/content/groups/public/")
    }
    maven { url = uri("https://repo.dmulloy2.net/repository/public/") }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.1.0")
}

val targetJavaVersion = 17
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}
tasks {
    withType<JavaCompile>().configureEach {
        if (targetJavaVersion >= 10 || JavaVersion.current().isJava10Compatible()) {
            options.release.set(targetJavaVersion)
        }
    }
    withType<ProcessResources>().configureEach {
        inputs.property("version", version)
        filesMatching("plugin.yml") {
            expand(mapOf("version" to version))
        }
    }
    register<Copy>("copyPlugin") {
        dependsOn("jar")
        from("build/libs")
        into("run/plugins")
        rename { "ServerSideTranslations.jar" }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    // register task called runDev that depends on copyPlugin that runs run/paper.jar
    register<Exec>("runDev") {
        dependsOn("copyPlugin")
        commandLine("java", "-jar", "run/paper.jar")
    }
    jar {
        dependsOn("shadowJar")
    }
}