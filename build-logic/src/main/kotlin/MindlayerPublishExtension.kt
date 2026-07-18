import org.gradle.api.provider.Property

/**
 * Per-module POM metadata for the `mindlayer.android.library.published`
 * convention. The Maven `artifactId` is always the Gradle project name, so only
 * the human-readable POM name + description vary between modules.
 */
interface MindlayerPublishExtension {
    val pomName: Property<String>
    val pomDescription: Property<String>
}
