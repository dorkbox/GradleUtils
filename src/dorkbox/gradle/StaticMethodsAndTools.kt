package dorkbox.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import java.io.File
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.declaredMemberProperties

open class StaticMethodsAndTools(private val project: Project) {
    /**
     * Maps the property (key/value) pairs of a property file onto the specified target object.
     */
    fun load(propertyFile: String, targetObject: Any) {
        val kClass = targetObject::class

        val propsFile = File(propertyFile).normalize()
        if (propsFile.canRead()) {
            println("\tLoading custom property data from: [$propsFile]")

            val props = Properties()
            propsFile.inputStream().use {
                props.load(it)
            }

            val extraProperties = kClass.declaredMemberProperties.filterIsInstance<KMutableProperty<String>>()
            props.forEach { (k, v) -> run {
                val key = k as String
                val value = v as String

                val member = extraProperties.find { it.name == key }
                if (member != null) {
                    member.setter.call(kClass.objectInstance, value)
                }
                else {
                    project.extensions.extraProperties.set(k, v)
                }
            }}
        }
    }

    /**
     * Validates the minimum version of gradle supported
     */
    fun minVersion(version: String) {
        val compared = org.gradle.util.GradleVersion.current().compareTo(org.gradle.util.GradleVersion.version(version))
        if (compared == -1) {
            throw GradleException("This project requires Gradle $version or higher.")
        }
    }

    /**
     * Validates the maximum version of gradle supported
     */
    fun maxVersion(version: String) {
        val compared = org.gradle.util.GradleVersion.current().compareTo(org.gradle.util.GradleVersion.version(version))
        if (compared == 1) {
            throw GradleException("This project requires Gradle $version or lower.")
        }
    }

    /**
     * Resolves all child dependencies of the project
     */
    fun resolveDependencies(): List<ResolvedArtifact> {
        val configuration = project.configurations.getByName("default") as Configuration

        val includedDeps = mutableSetOf<ResolvedDependency>()
        val depsToSearch = LinkedList<ResolvedDependency>()
        depsToSearch.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)

        return includedDeps.flatMap {
            it.moduleArtifacts
        }
    }


    /**
     * Recursively resolves all dependencies of the project
     */
    fun resolveAllDependencies(): List<ResolvedArtifact> {
        val configuration = project.configurations.getByName("default") as Configuration

        val includedDeps = mutableSetOf<ResolvedDependency>()
        val depsToSearch = LinkedList<ResolvedDependency>()
        depsToSearch.addAll(configuration.resolvedConfiguration.firstLevelModuleDependencies)

        while (depsToSearch.isNotEmpty()) {
            val dep = depsToSearch.removeFirst()
            includedDeps.add(dep)

            depsToSearch.addAll(dep.children)
        }

        return includedDeps.flatMap {
            it.moduleArtifacts
        }
    }

    /**
     * Fix the compiled output from intellij to be SEPARATE from gradle.
     */
    fun fixIntellijPaths(location: String = "${project.buildDir}/classes-intellij") {
        // https://discuss.gradle.org/t/can-a-plugin-itself-add-buildscript-dependencies-and-then-apply-a-plugin/25039/4
        apply(project, "idea")

        // put idea in it's place! Not having this causes SO MANY PROBLEMS when building modules
        idea(project) {
            // https://youtrack.jetbrains.com/issue/IDEA-175172
            module {
                val mainDir = File(location)
                it.outputDir = mainDir
                it.testOutputDir = mainDir
            }
        }
    }

    private fun idea(project: Project, configure: org.gradle.plugins.ide.idea.model.IdeaModel.() -> Unit): Unit =
            project.extensions.configure("idea", configure)

    // required to make sure the plugins are correctly applied. ONLY applying it to the project WILL NOT work.
    // The plugin must also be applied to the root project
    private fun apply(project: Project, id: String) {
        if (project.rootProject.pluginManager.findPlugin(id) == null) {
            project.rootProject.pluginManager.apply(id)
        }

        if (project.pluginManager.findPlugin(id) == null) {
            project.pluginManager.apply(id)
        }
    }
}
