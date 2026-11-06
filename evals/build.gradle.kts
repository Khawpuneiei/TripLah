plugins { application }

dependencies {
    "implementation"(project(":api"))
    "implementation"(project(":providers"))
}

application { mainClass.set("triplah.evals.EvalsKt") }

tasks.named<JavaExec>("run") { workingDir = rootDir; systemProperty("triplah.root", rootDir.absolutePath) }

tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Manual real-provider check (needs TRIPLAH_PROVIDERS=real and keys)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("triplah.evals.LiveCheckKt")
    workingDir = rootDir
    systemProperty("triplah.root", rootDir.absolutePath)
}
