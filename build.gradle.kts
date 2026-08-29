plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.crt"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Компилируем под Java 21; запуск на любом JDK >= 21
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-jetty")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Приложение раздаётся одним исполняемым bootJar. «Плоский» jar без зависимостей
// запустить нельзя, а в build/libs он создаёт второй файл — деплой потом гадает,
// какой из двух брать.
tasks.jar {
    enabled = false
}

tasks.bootRun {
    // Токен для /api/logs при локальном запуске (кнопка в IDEA / gradlew bootRun).
    // Внешняя переменная CT_AUTH_TOKEN имеет приоритет; в собранном jar работает
    // только внешняя переменная (по умолчанию токен пуст = эндпоинт закрыт).
    environment("CT_AUTH_TOKEN", System.getenv("CT_AUTH_TOKEN") ?: "e2e-token")
}
