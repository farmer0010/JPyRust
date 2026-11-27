configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            url.set("https://github.com/farmer0010/JPyRust")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("farmer0010")
                    name.set("farmer0010")
                    url.set("https://github.com/farmer0010")
                }
            }
            scm {
                url.set("https://github.com/farmer0010/JPyRust")
                connection.set("scm:git:https://github.com/farmer0010/JPyRust.git")
                developerConnection.set("scm:git:ssh://git@github.com/farmer0010/JPyRust.git")
            }
        }
    }
    repositories {
        maven {
            name = "centralStaging"
            url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
        }
    }
}

val signingKey = providers.environmentVariable("SIGNING_KEY")
if (signingKey.isPresent) {
    configure<SigningExtension> {
        useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("SIGNING_PASSWORD").orNull)
        sign(the<PublishingExtension>().publications)
    }
}
