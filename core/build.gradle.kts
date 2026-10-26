plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "vn.camhub"
version = "0.1.0-SNAPSHOT"

java {
    // SADD: Java 21. Máy build có thể là JDK mới hơn, bytecode vẫn nhắm 21.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

repositories {
    mavenCentral()
}

val testcontainersVersion = "1.20.6"

// Bản vá bảo mật mới hơn BOM của Spring Boot 3.5.16 (TS-08, quét OSV 26/10/2026; xem docs/test/security-review.md).
// Chỉ nâng bản vá trong cùng nhánh; bỏ khi Spring Boot có bản mới hơn các giá trị này.
extra["tomcat.version"] = "10.1.61"
extra["netty.version"] = "4.1.139.Final"
extra["jackson-bom.version"] = "2.21.8"
extra["rabbit-amqp-client.version"] = "5.37.0"
extra["commons-lang3.version"] = "3.21.0"
extra["log4j2.version"] = "2.26.1"

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
    }
}

// amqp-client 5.37 khai báo Netty 4.2 cho lớp IO Netty tùy chọn; Spring AMQP dùng socket thường. Bỏ để không trộn
// Netty 4.2 với Netty 4.1 của Lettuce (Redis).
dependencies.components.withModule("com.rabbitmq:amqp-client") {
    allVariants { withDependencies { removeIf { it.group == "io.netty" } } }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86") // Argon2id cho mật khẩu
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.minio:minio:8.6.0") // đọc/xóa clip đã xuất trong MinIO (US-20)
    implementation("org.springframework.boot:spring-boot-starter-mail") // thông báo email (US-34)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        // Test tích hợp (*IT) cần Docker, chạy riêng bằng task integrationTest
        excludeTags("integration")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Chạy test tích hợp cần Docker (Testcontainers)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }
