// Keep container-generated output separate from native Gradle builds in the mounted checkout.
allprojects {
    layout.buildDirectory.set(file("/tmp/hortinis-topology-build" + path.replace(':', '/')))
}
