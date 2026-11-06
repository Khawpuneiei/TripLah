plugins { application }

val ktor = "2.3.12"

dependencies {
    "implementation"(project(":providers"))
    "implementation"("io.ktor:ktor-server-core-jvm:$ktor")
    "implementation"("io.ktor:ktor-server-netty-jvm:$ktor")
    "implementation"("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    "implementation"("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    "implementation"("io.ktor:ktor-server-status-pages-jvm:$ktor")
    "implementation"("io.ktor:ktor-server-cors-jvm:$ktor")
    "implementation"("org.slf4j:slf4j-simple:2.0.16")
    "testImplementation"("io.ktor:ktor-server-test-host-jvm:$ktor")
}

application { mainClass.set("triplah.api.MainKt") }

tasks.named<JavaExec>("run") { workingDir = rootDir }
