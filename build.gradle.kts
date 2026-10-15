plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.clinic"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Swagger UI / OpenAPI — страница со всеми эндпоинтами в браузере
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")

    // PostgreSQL (для Docker-деплоя подключается тем же драйвером, меняется только URL через env)
    runtimeOnly("org.postgresql:postgresql")

    // ONNX Runtime — локальный инференс NLU-модели коллеги (model.onnx).
    // Версия зафиксирована: нативная библиотека тянет ~160 МБ, меняем осознанно.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")

    // sherpa-onnx — серверное распознавание речи (GigaAM, русский) для телефонии.
    // JNI-библиотека лежит внутри jar (native/linux-x64), тянет ~8 МБ.
    implementation("com.litongjava:sherpa-onnx-java-api:1.0.1")

    // Apache POI — чтение расписания из Excel.
    // Работает только при app.schedule.source=excel; при source=db файл не читается.
    implementation("org.apache.poi:poi:5.4.1")
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}


/**
 * Генерация тестового файла расписания Excel:
 *   ./gradlew genSchedule
 * Затем включить источник: SCHEDULE_SOURCE=excel
 */
tasks.register<JavaExec>("genSchedule") {
    group = "verification"
    description = "Создать тестовое расписание Excel (data/schedule.xlsx)"
    mainClass.set("com.clinic.backend.support.ScheduleExcelGenerator")
    classpath = sourceSets["main"].runtimeClasspath
}
