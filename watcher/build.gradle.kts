plugins { application }

dependencies {
    "implementation"(project(":api"))
    "implementation"(project(":providers"))
}

application { mainClass.set("triplah.watcher.WatcherKt") }
