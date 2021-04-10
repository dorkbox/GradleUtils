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

object DependencyScanner {

    /**
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun scan(
        project: Project,
        configurationName: String,
        projectDependencies: MutableList<Dependency>,
        existingNames: MutableSet<String>,
    ) {

        val config = project.configurations.getByName(configurationName)
        if (!config.isCanBeResolved) {
            return
        }

        config.resolvedConfiguration.lenientConfiguration.getFirstLevelModuleDependencies(org.gradle.api.specs.Specs.SATISFIES_ALL).forEach { dep ->
            // we know the FIRST series will exist
            val makeDepTree = makeDepTree(dep, existingNames)
            if (makeDepTree != null) {
                // it's only null if we've ALREADY scanned it
                if (!projectDependencies.contains(makeDepTree)) {
                    projectDependencies.add(makeDepTree)
                }
            }
        }
    }

    // how to resolve dependencies
    // NOTE: it is possible, when we have a project DEPEND on an older version of that project (ie: bootstrapped from an older version)
    //  we can have infinite recursion.
    //  This is a problem, so we limit how much a dependency can show up the the tree
    private fun makeDepTree(dep: ResolvedDependency, existingNames: MutableSet<String>): Dependency? {
        val module = dep.module.id
        val group = module.group
        val name = module.name
        val version = module.version

        if (!existingNames.contains("$group:$name")) {
            // println("Searching: $group:$name:$version")
            val artifacts: List<DependencyInfo> = dep.moduleArtifacts.map { artifact: ResolvedArtifact ->
                val artifactModule = artifact.moduleVersion.id
                DependencyInfo(artifactModule.group, artifactModule.name, artifactModule.version, artifact.file.absoluteFile)
            }

            val children = mutableListOf<Dependency>()
            dep.children.forEach {
                existingNames.add("$group:$name")
                val makeDep = makeDepTree(it, existingNames)
                if (makeDep != null) {
                    children.add(makeDep)
                }
            }

            return Dependency(group, name, version, artifacts, children.toList())
        }

        // we already have this dependency in our chain.
        return null
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

    data class Dependency(
        val group: String,
        val name: String,
        val version: String,
        val artifacts: List<DependencyInfo>,
        val children: List<Dependency>
    ) {

        fun mavenId(): String {
            return "$group:$name:$version"
        }

        override fun toString(): String {
            return mavenId()
        }
    }

    data class DependencyInfo(val group: String, val name: String, val version: String, val file: File) {
        val id: String
            get() {
                return "$group:$name:$version"
            }
    }

    data class MavenData(val group: String, val name: String, val version: String) {
        val id: String
            get() {
                return "$group:$name:$version"
            }
    }
}
