plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "de.puetsch"
version = "0.1.0"

val allureVersion = "2.35.5"
val playwrightVersion = "1.64.0"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
    testImplementation("com.microsoft.playwright:playwright:$playwrightVersion")
    testImplementation("io.qameta.allure:allure-junit5:$allureVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val allureAgent: Configuration by configurations.creating

dependencies {
    allureAgent("org.aspectj:aspectjweaver:1.9.25")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("allure.results.directory", layout.buildDirectory.dir("allure-results").get().asFile.absolutePath)
    jvmArgs("-javaagent:${allureAgent.singleFile}")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}
