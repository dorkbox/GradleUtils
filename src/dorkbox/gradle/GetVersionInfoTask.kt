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

import com.dorkbox.version.Version
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.util.GradleVersion
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URL

open class
GetVersionInfoTask : DefaultTask() {
    companion object {
        private val releaseMatcher = """^.*(<release>)(.*)(<\/release>)""".toRegex()
        private val versionMatcher = """^.*(<version>)(.*)(<\/version>)""".toRegex()

        private fun getLatestVersionInfo(repositories: List<String>, metadataUrl: String): Pair<Version?, MutableList<Version>> {
            val allVersions = mutableListOf<Version>()
            var largestReleaseVersion: Version? = null

            repositories.forEach { repoUrl ->
                try {
                    val url = URL(repoUrl + metadataUrl)
//                println("Trying: $url")
                    with(url.openConnection() as java.net.HttpURLConnection) {
                        InputStreamReader(inputStream).readLines().forEach { line ->
                            var matchResult = releaseMatcher.find(line)
                            if (matchResult != null) {
                                val (_, ver, _) = matchResult.destructured
                                val releaseVer = Version.from(ver)

                                if (largestReleaseVersion == null) {
                                    largestReleaseVersion = releaseVer
                                } else if (releaseVer.greaterThan(largestReleaseVersion)) {
                                    largestReleaseVersion = releaseVer
                                }
                            }

                            matchResult = versionMatcher.find(line)
                            if (matchResult != null) {
                                val (_, ver, _) = matchResult.destructured
                                allVersions.add(Version.from(ver))
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }

            return Pair(largestReleaseVersion, allVersions)
        }
    }

    @TaskAction
    fun run() {
        val staticMethodsAndTools = StaticMethodsAndTools(project)
        val repositories = staticMethodsAndTools.getProjectRepositoryUrls(project)

        val latestVersionInfo = mutableListOf<dorkbox.gradle.DependencyScanner.MavenData>()
        val oldVersionInfo = mutableListOf<Pair<dorkbox.gradle.DependencyScanner.MavenData, Pair<Version, MutableList<Version>>>>()
        val unknownVersionInfo = mutableListOf<dorkbox.gradle.DependencyScanner.MavenData>()

        val scriptDependencies = staticMethodsAndTools.resolveBuildScriptDependencies(project)

        // we can have MULTIPLE versions of a single dependency in use!!
        val mergedDeps = mutableMapOf<DependencyScanner.MavenData, MutableSet<DependencyScanner.MavenData>>()
        scriptDependencies.forEach { dep ->
            val deps = mergedDeps.getOrPut(DependencyScanner.MavenData(dep.group, dep.name, "")) { mutableSetOf() }
            deps.add(dep)
        }

        mergedDeps.forEach { (mergedDep, set) ->
            val latestData = getLatestVersionInfo(repositories, "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml")

            set.forEach { dep ->
                if (latestData.first != null) {
                    if (Version.from(dep.version) == latestData.first) {
                        latestVersionInfo.add(dep)
                    } else {
                        oldVersionInfo.add(Pair(dep, Pair(latestData.first!!, latestData.second)))
                    }
                } else {
                    unknownVersionInfo.add(dep)
                }
            }
        }

        // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
        println("------------------------------------------------------------")
        println("The following build script dependencies are using the latest release version:")
        latestVersionInfo.forEach { dep ->
            println("\t - ${dep.group}:${dep.version}")
        }

        if (oldVersionInfo.isNotEmpty()) {
            println()
            println("The following build script dependencies need updates:")

            oldVersionInfo.forEach { (dep, list) ->
                // list release version AND all other versions greater than my version
                val depVersion = Version.from(dep.version)
                val releaseVersion = list.first
                val possibleVersionChoices = list.second.filter { it.greaterThan(depVersion) }.toSet()

                println("\t - ${dep.group} [${dep.version} -> $releaseVersion]")
                if (possibleVersionChoices.size > 1) {
                    println("\t\tChoices: $possibleVersionChoices")
                }
            }
        }

        if (unknownVersionInfo.isNotEmpty()) {
            println()
            println("The following build script dependencies have unknown updates:")
            unknownVersionInfo.forEach { dep ->
                println("\t - ${dep.group}:${dep.version}")
            }
        }

        println()
        println()
        println()

        // now for project dependencies!
        mergedDeps.clear()
        latestVersionInfo.clear()
        oldVersionInfo.clear()
        unknownVersionInfo.clear()

        val resolveAllDependencies = staticMethodsAndTools.resolveAllDependencies(project)
        // we can have MULTIPLE versions of a single dependency in use!!
        resolveAllDependencies.forEach { dep ->
            val deps = mergedDeps.getOrPut(DependencyScanner.MavenData(dep.group, dep.name, "")) { mutableSetOf() }
            deps.add(DependencyScanner.MavenData(dep.group, dep.name, dep.version))
        }

        mergedDeps.forEach { (mergedDep, set) ->
            val latestData = getLatestVersionInfo(repositories, "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml")

            set.forEach { dep ->
                if (latestData.first != null) {
                    if (Version.from(dep.version) == latestData.first) {
                        latestVersionInfo.add(dep)
                    } else {
                        oldVersionInfo.add(Pair(dep, Pair(latestData.first!!, latestData.second)))
                    }
                } else {
                    unknownVersionInfo.add(dep)
                }
            }
        }

        println("------------------------------------------------------------")
        println("The following project dependencies are using the latest release version:")
        latestVersionInfo.forEach { dep ->
            println("\t - ${dep.group}:${dep.name}:${dep.version}")
        }

        if (oldVersionInfo.isNotEmpty()) {
            println()
            println("The following project dependencies need updates:")

            oldVersionInfo.forEach { (dep, list) ->
                // list release version AND all other versions greater than my version
                val depVersion = Version.from(dep.version)
                val releaseVersion = list.first
                val possibleVersionChoices = list.second.filter { it.greaterThan(depVersion) }.toSet()

                println("\t - ${dep.group} [${dep.version} -> $releaseVersion]")
                if (possibleVersionChoices.size > 1) {
                    println("\t\tChoices: $possibleVersionChoices")
                }
            }
        }

        if (unknownVersionInfo.isNotEmpty()) {
            println()
            println("The following project dependencies have unknown updates:")
            unknownVersionInfo.forEach { dep ->
                println("\t - ${dep.group}:${dep.name}:${dep.version}")
            }
        }
    }
}
