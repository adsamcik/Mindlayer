import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/**
 * Adds GitHub Packages publishing on top of `mindlayer.android.library`. The
 * Maven `artifactId` is the project name; each module only supplies its POM
 * name + description via the `mindlayerPublish { }` extension.
 *
 * Replaces the four copies of this `afterEvaluate { publishing { … } }` block
 * that previously lived in :sdk, :shared, :sdk-camerax and :sdk-camera-launcher.
 */
plugins {
    id("mindlayer.android.library")
    id("maven-publish")
}

val mindlayerPublish = extensions.create<MindlayerPublishExtension>("mindlayerPublish")

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = Mindlayer.GROUP_ID
                artifactId = project.name
                version = rootProject.extra["publishVersion"] as String

                pom {
                    name.set(mindlayerPublish.pomName)
                    description.set(mindlayerPublish.pomDescription)
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri(
                    "https://maven.pkg.github.com/" +
                        "${rootProject.extra["githubOwner"]}/${rootProject.extra["githubRepo"]}",
                )
                credentials {
                    username = rootProject.extra["githubOwner"] as String
                    password = rootProject.extra["githubToken"] as String
                }
            }
        }
    }
}
