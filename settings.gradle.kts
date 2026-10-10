plugins {
    // Позволяет Gradle самому скачать JDK, заданный в java.toolchain (build.gradle.kts).
    // Без этого сборка требовала бы, чтобы нужный JDK уже стоял на машине, и версия
    // байткода зависела бы от того, чем случайно запустили Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "crtlogs.ru"
