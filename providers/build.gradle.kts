plugins { `java-library` }

dependencies {
    "api"(project(":planner-core"))
    "api"("org.xerial:sqlite-jdbc:3.46.1.3")
}
