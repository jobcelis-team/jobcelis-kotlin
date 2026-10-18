import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.3.0"
    // Publishes to Maven Central (Central Portal): sources, javadoc, POM and GPG signature
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.jobcelis"
version = "1.3.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

mavenPublishing {
    // Credentials and signing key come from the ORG_GRADLE_PROJECT_* variables
    // set by .github/workflows/release.yml (repo secrets).
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    // Signed in CI (the key is only there); local builds and publishToMavenLocal stay unsigned
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("com.jobcelis", "jobcelis-kotlin", version.toString())

    pom {
        name.set("Jobcelis Kotlin SDK")
        description.set("Official Kotlin SDK for the Jobcelis Event Infrastructure Platform")
        url.set("https://github.com/jobcelis-team/jobcelis-kotlin")
        licenses {
            license {
                name.set("Business Source License 1.1")
                url.set("https://github.com/jobcelis-team/jobscelis/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("jobcelis-team")
                name.set("Vladimir Celi")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/jobcelis-team/jobcelis-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/jobcelis-team/jobcelis-kotlin.git")
            url.set("https://github.com/jobcelis-team/jobcelis-kotlin")
        }
    }
}
