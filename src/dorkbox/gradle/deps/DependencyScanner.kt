/*
 * Copyright 2026 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dorkbox.gradle.deps

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import java.io.File
import java.util.*

object DependencyScanner {

    /**
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     *
     *  NOTE: it is possible, when we have a project DEPEND on an older version of that project (ie: bootstrapped from an older version)
     *    we can have quite deep recursion. A project can never depend on itself, but we check if a project has already been added, and
     *    don't parse it more than once
     *
     *    This is an actual problem...
     */
    fun scan(project: Project, configurationNamePrefix: String, includeChildren: Boolean = true): List<DependencyData> {

        val projectDependencies = mutableListOf<DependencyData>()

        project.configurations.filter { it.name.startsWith(configurationNamePrefix) }
            .map { project.configurations.getByName(it.name) }
            .forEach { project.scan(it, projectDependencies, includeChildren) }

        return projectDependencies
    }


    private fun Project.scan(config: Configuration,
                             projectDependencies: MutableList<DependencyData> = mutableListOf<DependencyData>(),
                             includeChildren: Boolean = true): MutableList<DependencyData> {

        if (!config.isCanBeResolved) {
            return projectDependencies
        }

        try {
            config.resolve()
        } catch (_: Throwable) {
            println("Unable to resolve the $name configuration for the project ${project.name}")
        }

        val list = LinkedList<ResolvedDependency>()

        config.resolvedConfiguration.lenientConfiguration.firstLevelModuleDependencies.forEach { dep ->
            list.add(dep)
        }

        var next: ResolvedDependency
        while (list.isNotEmpty()) {
            next = list.poll()

            val module = next.module.id
            val group = module.group
            val name = module.name
            val version = module.version
            val mavenId = "$group:$name:$version"

            val artifacts = mutableListOf<Artifact>()
            next.moduleArtifacts.forEach {  artifact: ResolvedArtifact ->
                try {
                    val artifactModule = artifact.moduleVersion.id
                    artifacts.add(Artifact(artifactModule.group, artifactModule.name, artifactModule.version, artifact.file.absoluteFile))
                } catch (_: Exception) {
                    println("Error getting artifact for $mavenId, file: ${artifact.file.absoluteFile}")
                }
            }

            projectDependencies.add(DependencyData(group, name, version, artifacts))
            if (includeChildren) {
                list.addAll(next.children)
            }
        }

        return projectDependencies
    }

    /**
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     *
     *  NOTE: it is possible, when we have a project DEPEND on an older version of that project (ie: bootstrapped from an older version)
     *    we can have quite deep recursion. A project can never depend on itself, but we check if a project has already been added, and
     *    don't parse it more than once
     *
     *    This is an actual problem...
     */
    fun createTree(
        project: Project,
        configurationName: String,
        projectDependencies: MutableList<Dependency> = mutableListOf(),
        existingDeps: MutableMap<String, Dependency> = mutableMapOf(),
    ): List<Dependency> {
        val config = project.configurations.getByName(configurationName)
        if (!config.isCanBeResolved) {
            return projectDependencies
        }

        try {
            config.resolve()
        } catch (_: Throwable) {
            println("Unable to resolve the $configurationName configuration for the project ${project.name}")
        }

        // the root parent is tossed out, but not the topmost list of dependencies
        val rootParent = Dependency("", "", "", listOf(), projectDependencies)

        val list = LinkedList<ResolvedDependency>()
        val parentList = LinkedList<Dependency>()

        config.resolvedConfiguration.lenientConfiguration.firstLevelModuleDependencies.forEach { dep ->
            list.add(dep)
            parentList.add(rootParent)
        }


        var next: ResolvedDependency
        while (list.isNotEmpty()) {
            next = list.poll()

            val module = next.module.id
            val group = module.group
            val name = module.name
            val version = module.version
            val mavenId = "$group:$name:$version"

            if (!existingDeps.containsKey(mavenId)) {
                val artifacts = mutableListOf<Artifact>()
                next.moduleArtifacts.forEach {  artifact: ResolvedArtifact ->
                    try {
                        val artifactModule = artifact.moduleVersion.id
                        artifacts.add(Artifact(artifactModule.group, artifactModule.name, artifactModule.version, artifact.file.absoluteFile))
                    } catch (e: Exception) {
                        println("Error getting artifact for $mavenId, file: ${artifact.file.absoluteFile}")
                    }
                }

                val dependency = Dependency(group, name, version, artifacts, mutableListOf())

                // now add to our parent
                val parent = parentList.poll()
                (parent.children as MutableList).add(dependency)

                next.children.forEach { child ->
                    parentList.add(dependency)
                    list.add(child)
                }

                existingDeps[mavenId] = dependency
            }
        }

        return projectDependencies
    }

    /**
     * Flatten the dependency children
     */
    fun flattenDeps(dep: Dependency): List<Dependency> {
        val flatDeps = mutableSetOf<Dependency>()
        flattenDep(dep, flatDeps)
        return flatDeps.toList()
    }

    private fun flattenDep(dep: Dependency, flatDeps: MutableSet<Dependency>) {
        flatDeps.add(dep)
        dep.children.forEach {
            flattenDep(it, flatDeps)
        }
    }

    data class ProjectDependencies(val tree: List<Dependency>, val dependencies: List<Dependency>)

    data class DependencyData(
        val group: String,
        val name: String,
        val version: String,
        val artifacts: List<Artifact>
    ) {

        fun mavenId(): String {
            return "$group:$name:$version"
        }

        override fun toString(): String {
            return mavenId()
        }
    }

    data class Dependency(
        val group: String,
        val name: String,
        val version: String,
        val artifacts: List<Artifact>,
        val children: List<Dependency>
    ) {

        fun mavenId(): String {
            return "$group:$name:$version"
        }

        override fun toString(): String {
            return mavenId()
        }
    }

    data class Artifact(val group: String, val name: String, val version: String, val file: File) {
        val id: String
            get() {
                return "$group:$name:$version"
            }
    }

    data class Maven(val group: String, val name: String, val version: String = "") {
        val expandedName: String
            get() {
                return "$group:$name"
            }

        val id: String
            get() {
                return "$group:$name:$version"
            }
    }
}
