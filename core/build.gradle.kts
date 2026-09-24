import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM module: everything that talks to the user's server lives here, so it can be
// unit tested on a normal JVM against real (embedded) FTP/SFTP/WebDAV servers.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    // Android min SDK 26 understands Java 8 bytecode and APIs; stay on that level so the
    // code in this module can never call a JDK API that does not exist on the phone.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

dependencies {
    api(libs.commons.net)
    api(libs.jsch)
    api(libs.smbj)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.ftpserver.core)
}

tasks.withType<Test>().configureEach {
    // The embedded test servers write file names with accents to disk: the JVM needs a UTF-8 locale.
    environment("LC_ALL", "C.UTF-8")
    // Integration tests against external servers are opt-in (see ExternalServerTest).
    systemProperties(System.getProperties().filterKeys { it.toString().startsWith("scrigno.") }
        .mapKeys { it.key.toString() })
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
