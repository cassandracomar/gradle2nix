package org.nixos.gradle2nix

import org.apache.ivy.Ivy
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.parser.m2.PomReader
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser
import org.apache.ivy.plugins.repository.url.URLResource
import org.apache.ivy.plugins.repository.file.FileRepository
import org.apache.ivy.plugins.resolver.ChainResolver
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.query.ArtifactResolutionQuery
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository
import org.gradle.ivy.IvyDescriptorArtifact
import org.gradle.ivy.IvyModule
import org.gradle.kotlin.dsl.getArtifacts
import org.gradle.kotlin.dsl.withArtifacts
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.gradle.util.GradleVersion

enum class ConfigurationScope {
    BUILDSCRIPT,
    PLUGIN,
    PROJECT,
    SETTINGS
}

internal class ConfigurationResolverFactory(
    project: Project,
    scope: ConfigurationScope,
    repositories: List<ResolutionAwareRepository>
) {
    private val ivySettings = IvySettings().apply {
        defaultInit()
        // This doesn't appear to be used, but it's better to define it explicitly than to introduce
        // impurities into artifact resolution.
        setDefaultRepositoryCacheBasedir(project.buildDir.resolve("tmp/gradle2nix/_cache").absolutePath)
        setDictatorResolver(ChainResolver().also { chain ->
            chain.settings = this@apply
            for (resolver in resolvers) chain.add(resolver)
        })
    }

    private val resolvers = repositories
        .filterNot { it.createResolver().isLocal }
        .mapNotNull { it.repositoryResolver(project, scope, ivySettings) }

    fun create(dependencies: DependencyHandler): ConfigurationResolver =
        ConfigurationResolver(ivySettings, resolvers, dependencies)
}

internal class ConfigurationResolver(
    ivySettings: IvySettings,
    private val resolvers: List<RepositoryResolver>,
    private val dependencies: DependencyHandler
) {
    private val failed = mutableSetOf<ArtifactIdentifier>()
    private val ivy = Ivy.newInstance(ivySettings)

    val unresolved: List<ArtifactIdentifier> = failed.toList()

    fun resolve(configuration: Configuration): List<DefaultArtifact> {
        val resolved = configuration.resolvedConfiguration.lenientConfiguration

        val topLevelMetadata = resolved.firstLevelModuleDependencies
            .flatMap { resolveMetadata(it.moduleGroup, it.moduleName, it.moduleVersion) }

        val reresolvedArtifacts = resolved.unresolvedModuleDependencies.flatMap {
            listOf(
                DefaultArtifactIdentifier(
                    group = it.selector.group,
                    name = it.selector.name,
                    version = it.selector.version ?: "",
                    type = "module"
                ),
                DefaultArtifactIdentifier(
                    group = it.selector.group,
                    name = it.selector.name,
                    version = it.selector.version ?: "",
                    type = "pom"
                ),
                DefaultArtifactIdentifier(
                    group = it.selector.group,
                    name = it.selector.name,
                    version = it.selector.version ?: "",
                    type = "jar"
                ),
            )
        }
        .flatMap { 
            val metadata = if (it.type == "pom") {
                resolveMetadata(it.group, it.name, it.version)
            } else {
                listOf()
            }
            metadata + resolvers.mapNotNull{ resolver -> 
                resolver.resolve(it).also { resolvedArtifact ->
                    if (resolvedArtifact == null) {
                        failed.add(it)
                    }
                }
            } 
        }

        println("unresolved artifacts: " + failed)
        println("metadata: " + topLevelMetadata)
        println("resolved artifacts: " + resolved.artifacts)

        val allArtifacts = resolved.artifacts
            .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
            .flatMap(::resolve)

        return (topLevelMetadata + allArtifacts + reresolvedArtifacts).filter { it.urls.isNotEmpty() }
    }

    private fun resolve(resolvedArtifact: ResolvedArtifact): List<DefaultArtifact> {
        val componentId = resolvedArtifact.id.componentIdentifier as ModuleComponentIdentifier

        val artifactId = DefaultArtifactIdentifier(
            group = componentId.group,
            name = componentId.module,
            version = componentId.version,
            type = resolvedArtifact.type,
            extension = resolvedArtifact.extension ?: "",
            classifier = resolvedArtifact.classifier
        )

        val sha256 = resolvedArtifact.computeHash()
        val artifacts = resolvers.mapNotNull { it.resolve(artifactId, sha256) }.merge()
        if (artifacts.isEmpty()) failed.add(artifactId)
        return artifacts + componentId.run { resolveMetadata(group, module, version) }
    }

    private fun resolveMetadata(
        group: String,
        name: String,
        version: String
    ): List<DefaultArtifact> {
        return resolvePoms(group, name, version) +
            resolveDescriptors(group, name, version) +
            resolveGradleMetadata(group, name, version)
    }

    private fun resolvePoms(
        group: String,
        name: String,
        version: String
    ): List<DefaultArtifact> {
        return dependencies.createArtifactResolutionQuery()
            .forModuleCompat(group, name, version)
            .withArtifacts(MavenModule::class, MavenPomArtifact::class)
            .execute()
            .resolvedComponents
            .flatMap { it.getArtifacts(MavenPomArtifact::class) }
            .filterIsInstance<ResolvedArtifactResult>()
            .flatMap { it.withParentPoms() }
            .flatMap { resolvedPom ->
                val componentId = resolvedPom.id.componentIdentifier as ModuleComponentIdentifier
                val artifactId = DefaultArtifactIdentifier(
                    group = componentId.group,
                    name = componentId.module,
                    version = componentId.version,
                    type = "pom"
                )
                val artifactIdJar = DefaultArtifactIdentifier(
                    group = componentId.group,
                    name = componentId.module,
                    version = componentId.version,
                    type = "jar"
                )
                // Intentionally not computing hash from the cached result; see ResolvedArtifact.computeHash() below.
                val artifacts = resolvers.mapNotNull { it.resolve(artifactId) }.merge() + resolvers.mapNotNull { it.resolve(artifactIdJar) }.merge() 
                if (artifacts.isEmpty()) failed.add(artifactId)
                artifacts
            }
    }

    private fun resolveDescriptors(
        group: String,
        name: String,
        version: String
    ): List<DefaultArtifact> {
        return dependencies.createArtifactResolutionQuery()
            .forModuleCompat(group, name, version)
            .withArtifacts(IvyModule::class, IvyDescriptorArtifact::class)
            .execute()
            .resolvedComponents
            .flatMap { it.getArtifacts(IvyDescriptorArtifact::class) }
            .filterIsInstance<ResolvedArtifactResult>()
            .flatMap { it.withParentDescriptors() }
            .flatMap { resolvedDesc ->
                val componentId = resolvedDesc.id.componentIdentifier as ModuleComponentIdentifier
                val artifactId = DefaultArtifactIdentifier(
                    group = componentId.group,
                    name = componentId.module,
                    version = componentId.version,
                    type = "ivy",
                    extension = "xml"
                )
                // Intentionally not computing hash from the cached result; see ResolvedArtifact.computeHash() below.
                val artifacts = resolvers.mapNotNull { it.resolve(artifactId) }.merge()
                if (artifacts.isEmpty()) failed.add(artifactId)
                artifacts
            }
    }

    private fun resolveGradleMetadata(
        group: String,
        name: String,
        version: String
    ): List<DefaultArtifact> {
        val artifactId = DefaultArtifactIdentifier(
            group = group,
            name = name,
            version = version,
            type = "module"
        )
        val artifacts = resolvers.mapNotNull { it.resolve(artifactId) }.merge()
        if (artifacts.isEmpty()) failed.add(artifactId)
        return artifacts
    }

    private fun ResolvedArtifactResult.parentPom(): ResolvedArtifactResult? {
        val resource = URLResource(file.toURI().toURL())
        val reader = PomReader(resource.url, resource)

        return if (reader.hasParent()) {
            dependencies.createArtifactResolutionQuery()
                .forModuleCompat(reader.parentGroupId, reader.parentArtifactId, reader.parentVersion)
                .withArtifacts(MavenModule::class, MavenPomArtifact::class)
                .execute()
                .resolvedComponents
                .flatMap { it.getArtifacts(MavenPomArtifact::class) }
                .filterIsInstance<ResolvedArtifactResult>()
                .firstOrNull()
        } else {
            null
        }
    }

    private fun ResolvedArtifactResult.withParentPoms(): List<ResolvedArtifactResult> =
        generateSequence(this) { it.parentPom() }.toList()

    private fun ResolvedArtifactResult.parentDescriptors(seen: Set<ComponentArtifactIdentifier>): List<ResolvedArtifactResult> {
        val url = file.toURI().toURL()
        val parser = XmlModuleDescriptorParser.getInstance()

        val descriptor = parser.parseDescriptor(ivy.settings, url, false)

        return descriptor.inheritedDescriptors.mapNotNull { desc ->
            dependencies.createArtifactResolutionQuery()
                .forModuleCompat(
                    desc.parentRevisionId.organisation,
                    desc.parentRevisionId.name,
                    desc.parentRevisionId.revision
                )
                .withArtifacts(IvyModule::class, IvyDescriptorArtifact::class)
                .execute()
                .resolvedComponents
                .flatMap { it.getArtifacts(IvyDescriptorArtifact::class) }
                .filterIsInstance<ResolvedArtifactResult>()
                .firstOrNull()
        }.filter { it.id !in seen }
    }

    private fun ResolvedArtifactResult.withParentDescriptors(): List<ResolvedArtifactResult> {
        val seen = mutableSetOf<ComponentArtifactIdentifier>()
        return generateSequence(listOf(this)) { descs ->
            val parents = descs.flatMap { it.parentDescriptors(seen) }
            seen.addAll(parents.map(ResolvedArtifactResult::getId))
            parents.takeUnless { it.isEmpty() }
        }.flatten().distinct().toList()
    }
}

private fun ArtifactResolutionQuery.forModuleCompat(
    group: String,
    name: String,
    version: String
): ArtifactResolutionQuery {
    return if (GradleVersion.current() >= GradleVersion.version("4.5")) {
        forModule(group, name, version)
    } else {
        forComponents(ModuleComponentId(group, name, version))
    }
}

private data class ModuleComponentId(
    private val moduleId: ModuleId,
    private val version: String
) : ModuleComponentIdentifier {

    constructor(
        group: String,
        name: String,
        version: String
    ) : this(ModuleId(group, name), version)

    override fun getGroup(): String = moduleId.group
    override fun getModule(): String = moduleId.name
    override fun getVersion(): String = version
    override fun getModuleIdentifier(): ModuleIdentifier = moduleId
    override fun getDisplayName(): String =
        arrayOf(group, module, version).joinToString(":")
}

private data class ModuleId(
    private val group: String,
    private val name: String
) : ModuleIdentifier {
    override fun getGroup(): String = group
    override fun getName(): String = name
}

private fun List<DefaultArtifact>.merge(): List<DefaultArtifact> {
    return groupingBy { it.id }
        .reduce { _, dest, next -> dest.copy(urls = dest.urls + next.urls) }
        .values.toList()
}

private fun ResolvedArtifact.computeHash(): String? {
    // Hack: Some POM files are served with CRLF line endings, e.g. javax.servlet:javax.servlet-api:3.1.0.
    // Gradle stores these normalized with LF line endings, which will not match the eventual hash
    // of the fixed-output derivation which produces the POM artifact.
    //
    // A similar issue can exist for Apache Ivy; see https://issues.apache.org/jira/browse/IVY-1156.
    //
    // Ignore these artifacts, and defer hash calculation to RepositoryResolver.
    if (type == "pom" || type == "ivy") {
        return null
    }

    return file.sha256()
}