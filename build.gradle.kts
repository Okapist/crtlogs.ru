plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.crt"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Компиляция и тесты идут на JDK 25 независимо от того, каким JDK запущен Gradle:
// toolchain при необходимости скачивается (см. foojay-resolver в settings.gradle.kts).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Без этого javac пишет только «Some input files use a deprecated API» без имён —
// то есть предупреждение есть, а починить по нему нечего.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
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
    // Spring Boot 4 разнёс срезы тестов по модулям: @AutoConfigureMockMvc (HTTP-контракт
    // в ApiControllerTest) в starter-test больше не приезжает.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Тесты идут в зоне, отличной от UTC. Даты хранятся как UTC-локальное время, и
    // ошибки вида «сравнили с часами машины» в UTC-окружении не видны вовсе — а CI
    // работает именно в UTC. Полчаса смещения ловят заодно предположение, что смещение
    // всегда целое число часов; в Asia/Kolkata нет перехода на летнее время, поэтому
    // несуществующих локальных меток времени не бывает.
    systemProperty("user.timezone", "Asia/Kolkata")
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
