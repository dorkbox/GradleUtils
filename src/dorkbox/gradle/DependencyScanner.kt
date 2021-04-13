/*
 * Copyright 2021 dorkbox, llc
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
package dorkbox.gradle

import org.gradle.api.Project
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
    fun scan(project: Project, configurationName: String, includeChildren: Boolean = true): List<DependencyData> {

        val projectDependencies = mutableListOf<DependencyData>()
        val config = project.configurations.getByName(configurationName)
        if (!config.isCanBeResolved) {
            return projectDependencies
        }

        config.resolve()

        val list = LinkedList<ResolvedDependency>()

        config.resolvedConfiguration.lenientConfiguration.getFirstLevelModuleDependencies(org.gradle.api.specs.Specs.SATISFIES_ALL).forEach { dep ->
            list.add(dep)
        }

        var next: ResolvedDependency
        while (list.isNotEmpty()) {
            next = list.poll()

            val module = next.module.id
            val group = module.group
            val name = module.name
            val version = module.version

            val artifacts: List<Artifact> = next.moduleArtifacts.map { artifact: ResolvedArtifact ->
                val artifactModule = artifact.moduleVersion.id
                Artifact(artifactModule.group, artifactModule.name, artifactModule.version, artifact.file.absoluteFile)
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
        projectDependencies: MutableList<Dependency>,
        existingDeps: MutableMap<String, Dependency>,
    ) {

        val config = project.configurations.getByName(configurationName)
        if (!config.isCanBeResolved) {
            return
        }

        config.resolve()

        // the root parent is tossed out, but not the topmost list of dependencies
        val rootParent = Dependency("", "", "", listOf(), projectDependencies)

        val parentList = LinkedList<Dependency>()
        val list = LinkedList<ResolvedDependency>()

        config.resolvedConfiguration.lenientConfiguration.getFirstLevelModuleDependencies(org.gradle.api.specs.Specs.SATISFIES_ALL).forEach { dep ->
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
                val artifacts: List<Artifact> = next.moduleArtifacts.map { artifact: ResolvedArtifact ->
                    val artifactModule = artifact.moduleVersion.id
                    Artifact(artifactModule.group, artifactModule.name, artifactModule.version, artifact.file.absoluteFile)
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

    data class Maven(val group: String, val name: String, val version: String) {
        val id: String
            get() {
                return "$group:$name:$version"
            }
    }
}
