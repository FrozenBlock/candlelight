plugins {
    java
    `java-gradle-plugin`
    `maven-publish`
}

val plugin_version: String by project
val plugin_group: String by project
val plugin_id: String by project
val plugin_class: String by project

group = plugin_group

val runNumber: String? = System.getenv("GITHUB_RUN_NUMBER")
version = if (runNumber != null) "$plugin_version.$runNumber" else plugin_version

repositories {
    mavenLocal() // to find locally published dependencies if needed
    mavenCentral()

    maven("https://maven.neoforged.net/releases") {
        name = "NeoForge"
    }

    // Our publishing repo
    maven("https://registry.somethingcatchy.net/repository/maven-releases/")
}

val env: Map<String, String> = System.getenv()

publishing {
    repositories {
        mavenLocal()

        val mavenUrl = env["MAVEN_URL"]
        val mavenUsername = env["MAVEN_USERNAME"]
        val mavenPassword = env["MAVEN_PASSWORD"]
        if (mavenUrl != null && mavenUsername != null && mavenPassword != null) {
            maven(mavenUrl) {
                name = "FrozenBlock"
                credentials {
                    username = mavenUsername
                    password = mavenPassword
                }
            }
        }
    }
}

dependencies {
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation("com.google.code.gson:gson:2.11.0")

    //implementation("net.neoforged:srgutils:1.0.11")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0") // JUnit 5
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
}

gradlePlugin {
    plugins {
        create("candlelightPlugin") {
            id = plugin_id
            implementationClass = plugin_class
        }
    }
}

tasks.register("buildAndPublish") {
    group = "build"

    dependsOn("clean")
    dependsOn("build")
    dependsOn("publishToMavenLocal")
}
