plugins { application }

dependencies {
    "implementation"(project(":api"))
    "implementation"(project(":providers"))
}

application { mainClass.set("triplah.mcp.McpServerKt") }

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Single runnable jar for Claude Code's MCP config."
    archiveFileName.set("triplah-mcp.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "triplah.mcp.McpServerKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}
