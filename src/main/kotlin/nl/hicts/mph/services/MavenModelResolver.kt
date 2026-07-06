package nl.hicts.mph.services

import org.apache.maven.model.Model
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository
import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import java.io.File

typealias MavenDependency = org.apache.maven.model.Dependency
typealias AetherDependency = org.eclipse.aether.graph.Dependency

class MavenModelResolver(private val workspaceProjects: Map<String, File> = emptyMap()) {

    private val modelBuilder: ModelBuilder = DefaultModelBuilderFactory().newInstance()
    private val localRepositoryPath = File(System.getProperty("user.home"), ".m2/repository")
    
    private val repositorySystem: RepositorySystem = newRepositorySystem()
    private val session: RepositorySystemSession = newSession(repositorySystem)
    private val remoteRepositories = mutableListOf(
        RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build()
    )

    private fun newRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        return locator.getService(RepositorySystem::class.java)
    }

    private fun newSession(system: RepositorySystem): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepo = LocalRepository(localRepositoryPath)
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepo)
        
        // Default selectors from MavenRepositorySystemUtils exclude provided and test transitive dependencies.
        // For SBOM we want to include more, so we can customize or remove selectors.
        // But for now, let's keep it mostly as is and see.
        
        return session
    }

    fun resolveEffectiveModel(pomFile: File): Model {
        return resolveModelResult(pomFile).effectiveModel
    }

    fun resolveModelResult(pomFile: File): ModelBuildingResult {
        val modelBuildingRequest = DefaultModelBuildingRequest()
        modelBuildingRequest.pomFile = pomFile
        modelBuildingRequest.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        modelBuildingRequest.isProcessPlugins = false
        modelBuildingRequest.isTwoPhaseBuilding = false
        modelBuildingRequest.systemProperties = System.getProperties()
        modelBuildingRequest.modelResolver = RepositoryModelResolver(
            repositorySystem,
            session,
            remoteRepositories,
            workspaceProjects
        )

        return modelBuilder.build(modelBuildingRequest)
    }

    fun resolveModel(groupId: String, artifactId: String, version: String): Model {
        val resolver = RepositoryModelResolver(
            repositorySystem,
            session,
            remoteRepositories,
            workspaceProjects
        )
        val modelSource = resolver.resolveModel(groupId, artifactId, version)

        val modelBuildingRequest = DefaultModelBuildingRequest()
        modelBuildingRequest.modelSource = modelSource
        modelBuildingRequest.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        modelBuildingRequest.isProcessPlugins = false
        modelBuildingRequest.isTwoPhaseBuilding = false
        modelBuildingRequest.systemProperties = System.getProperties()
        modelBuildingRequest.modelResolver = resolver

        val modelBuildingResult = modelBuilder.build(modelBuildingRequest)
        return modelBuildingResult.effectiveModel
    }

    fun resolveDependencyTree(groupId: String, artifactId: String, version: String): org.eclipse.aether.graph.DependencyNode {
        val artifact = DefaultArtifact(groupId, artifactId, "", "pom", version)
        
        val descriptorRequest = ArtifactDescriptorRequest(artifact, remoteRepositories, null)
        val descriptorResult = repositorySystem.readArtifactDescriptor(session, descriptorRequest)
        
        val collectRequest = CollectRequest()
        collectRequest.root = AetherDependency(DefaultArtifact(groupId, artifactId, "", "jar", version), "compile")
        collectRequest.dependencies = descriptorResult.dependencies
        collectRequest.managedDependencies = descriptorResult.managedDependencies
        collectRequest.repositories = remoteRepositories
        
        val dependencyRequest = DependencyRequest(collectRequest, null)
        val dependencyResult = repositorySystem.resolveDependencies(session, dependencyRequest)
        
        return dependencyResult.root
    }

    fun resolveDependencies(groupId: String, artifactId: String, version: String): List<Artifact> {
        return resolveAllDependencies(groupId, artifactId, version).map { it.artifact }
    }

    fun resolveAllDependencies(groupId: String, artifactId: String, version: String): List<AetherDependency> {
        val artifact = DefaultArtifact(groupId, artifactId, "", "pom", version)
        
        val descriptorRequest = ArtifactDescriptorRequest(artifact, remoteRepositories, null)
        val descriptorResult = repositorySystem.readArtifactDescriptor(session, descriptorRequest)
        
        val collectRequest = CollectRequest()
        collectRequest.root = AetherDependency(DefaultArtifact(groupId, artifactId, "", "jar", version), "")
        collectRequest.dependencies = descriptorResult.dependencies
        collectRequest.managedDependencies = descriptorResult.managedDependencies
        collectRequest.repositories = remoteRepositories
        
        val dependencyRequest = DependencyRequest(collectRequest, null)
        val dependencyResult = repositorySystem.resolveDependencies(session, dependencyRequest)
        
        val result = mutableListOf<AetherDependency>()
        fun flatten(node: org.eclipse.aether.graph.DependencyNode) {
            node.dependency?.let { result.add(it) }
            node.children.forEach { flatten(it) }
        }
        flatten(dependencyResult.root)
        
        return result.filter { it.artifact.groupId != groupId || it.artifact.artifactId != artifactId }
            .distinctBy { 
                val a = it.artifact
                "${a.groupId}:${a.artifactId}:${a.version}"
            }
    }

    private class RepositoryModelResolver : ModelResolver {
        private val system: RepositorySystem
        private val session: RepositorySystemSession
        private val repositories: MutableList<RemoteRepository>
        private val workspaceProjects: Map<String, File>

        constructor(
            system: RepositorySystem,
            session: RepositorySystemSession,
            repositories: List<RemoteRepository>,
            workspaceProjects: Map<String, File>
        ) {
            this.system = system
            this.session = session
            this.repositories = repositories.toMutableList()
            this.workspaceProjects = workspaceProjects
        }

        private constructor(copy: RepositoryModelResolver) {
            this.system = copy.system
            this.session = copy.session
            this.repositories = copy.repositories.toMutableList()
            this.workspaceProjects = copy.workspaceProjects
        }

        override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource {
            val key = "$groupId:$artifactId:$version"
            val workspacePom = workspaceProjects[key]
            if (workspacePom != null) {
                return FileModelSource(workspacePom)
            }

            val artifact = DefaultArtifact(groupId, artifactId, "", "pom", version)
            val request = ArtifactRequest(artifact, repositories, null)
            
            return try {
                val result = system.resolveArtifact(session, request)
                FileModelSource(result.artifact.file)
            } catch (e: ArtifactResolutionException) {
                throw UnresolvableModelException(e.message, groupId, artifactId, version, e)
            }
        }

        override fun resolveModel(parent: Parent): ModelSource {
            return resolveModel(parent.groupId, parent.artifactId, parent.version)
        }

        override fun resolveModel(dependency: MavenDependency): ModelSource {
            return resolveModel(dependency.groupId, dependency.artifactId, dependency.version)
        }

        override fun addRepository(repository: Repository) {
            addRepository(repository, false)
        }

        override fun addRepository(repository: Repository, replace: Boolean) {
            if (repositories.any { it.id == repository.id }) {
                if (replace) {
                    repositories.removeIf { it.id == repository.id }
                } else {
                    return
                }
            }
            repositories.add(RemoteRepository.Builder(repository.id, repository.layout, repository.url).build())
        }

        override fun newCopy(): ModelResolver {
            return RepositoryModelResolver(this)
        }
    }
}
