plugins {
    base
    id("eclipse")
}

group = "org.nixos.gradle2nix"
version = property("VERSION") ?: "unspecified"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

allprojects {
    apply(plugin = "eclipse")
    eclipse {
        classpath {
            isDownloadJavadoc = true
            isDownloadSources = true
        }
    }
    plugins.withType<JavaBasePlugin> {
        this@allprojects.withConvention(JavaPluginConvention::class) {
            sourceSets.all {
                configurations {
                    named(compileClasspathConfigurationName) {
                        resolutionStrategy.activateDependencyLocking()
                    }
                    named(runtimeClasspathConfigurationName) {
                        resolutionStrategy.activateDependencyLocking()
                    }
                }
            }

            tasks.register("lock") {
                doFirst {
                    assert(gradle.startParameter.isWriteDependencyLocks)
                    file("buildscript-gradle.lockfile").delete()
                    file("gradle.lockfile").delete()
                }
                doLast {
                    configurations.matching { it.isCanBeResolved }.all { resolve() }
                }
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.0.1"
        distributionType = Wrapper.DistributionType.BIN
    }
}
