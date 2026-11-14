// The implicit buildSrc settings in Gradle6 do not inherit the parent init's
// plugin repository hook. Use the same restored, checksummed public repository.
pluginManagement {
    repositories {
        System.getenv("NEWSCOPE_MAVEN_REPO")?.let { maven { url = uri(it) } }
        gradlePluginPortal()
    }
}
