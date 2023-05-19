package org.nixos.gradle2nix

import org.gradle.api.Project
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository
import org.gradle.api.internal.artifacts.DependencyResolutionServices
import org.gradle.api.internal.artifacts.dependencies.DefaultPluginDependency
import org.gradle.api.internal.catalog.DefaultExternalDependencyFactory
import org.gradle.api.artifacts.DependencyArtifactSelector
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.plugin.management.PluginRequest
import org.gradle.plugin.use.internal.PluginDependencyResolutionServices
import org.gradle.kotlin.dsl.buildscript
import org.gradle.kotlin.dsl.repositories
import javax.inject.Inject

internal open class PluginResolver @Inject constructor(
    private val project: Project,
    private val pluginDependencyResolutionServices: PluginDependencyResolutionServices,
    private val pluginRepositories: List<ArtifactRepository>,
) {
    fun resolve(pluginRequests: List<PluginRequest>): List<DefaultArtifact> {
        println("repositories: " + pluginRepositories)
        val resolver = ConfigurationResolverFactory(
            project,
            ConfigurationScope.PLUGIN,
            pluginRepositories.filterIsInstance<ResolutionAwareRepository>(),
        ).create(project.buildscript.dependencies)

        val markerDependencies = pluginRequests.map { request ->
            request.module?.let { module ->
                println("adding module dependency: " + module.group + ":" + module.name + ":" + module.version)
                ApiHack.defaultExternalModuleDependency(module.group, module.name, module.version) as Dependency
            } ?: request.id.id.let { id ->
                println("adding id dependency: " + id + ":$id.gradle.plugin:" + request.version)
                ApiHack.defaultExternalModuleDependency(id, "$id.gradle.plugin", request.version) as Dependency
            }
        }
        val configuration = project.buildscript.configurations.detachedConfiguration(*markerDependencies.toTypedArray())
        return resolver.resolve(configuration.apply {
            dependencyConstraints.addAll(project.buildscript.configurations.flatMap { it.allDependencyConstraints })
        })
    }
}
