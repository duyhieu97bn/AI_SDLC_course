// Kiểm thử tải API (TS-08.1): Gatling Java DSL. Chạy bằng ./run.sh (container gắn mạng camhub_backend).
plugins {
    java
    id("io.gatling.gradle") version "3.13.5"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

gatling {
    // Tham số của mô phỏng: -Dusers, -Dminutes, -DrampSeconds, -DbaseUrl (xem ApiLoadSimulation).
    // Không ghi đè jvmArgs: mặc định của plugin có --add-opens mà Gatling cần trên Java 17+.
    systemProperties = System.getProperties()
        .filterKeys { it.toString() in setOf("users", "minutes", "rampSeconds", "baseUrl") }
        .mapKeys { it.key.toString() }
}
