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
        private val versionMatcher = """^.*(<release>)(.*)(<\/release>)""".toRegex()

        private fun getLatestVersionInfo(repositories: List<String>, metadataUrl: String): Version? {
            var largestReleaseVersion: Version? = null

            repositories.forEach { repoUrl ->
                try {
                    val url = URL(repoUrl + metadataUrl)
//                println("Trying: $url")
                    with(url.openConnection() as java.net.HttpURLConnection) {
                        val lastVersion = InputStreamReader(inputStream).readLines().lastOrNull { line ->
                            // only care about <release>!
                            // <release>1.0</release>
                            line.matches(versionMatcher)
                        }

                        val matchResult = versionMatcher.find(lastVersion ?: "")
                        if (matchResult != null) {
                            val (_, ver, _) = matchResult.destructured
                            val releaseVer = Version.from(ver)

                            if (largestReleaseVersion == null) {
                                largestReleaseVersion = releaseVer
                            } else if (releaseVer.greaterThan(largestReleaseVersion)) {
                                largestReleaseVersion = releaseVer
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }

            return largestReleaseVersion
        }
    }

    @TaskAction
    fun run() {
        val staticMethodsAndTools = StaticMethodsAndTools(project)
        val repositories = staticMethodsAndTools.getProjectRepositoryUrls(project)

        val latestVersionInfo = mutableListOf<dorkbox.gradle.DependencyScanner.MavenData>()
        val oldVersionInfo = mutableListOf<Pair<dorkbox.gradle.DependencyScanner.MavenData, Version>>()
        val unknownVersionInfo = mutableListOf<dorkbox.gradle.DependencyScanner.MavenData>()

        val scriptDependencies = staticMethodsAndTools.resolveBuildScriptDependencies(project)

        // we can have MULTIPLE versions of a single dependency in use!!
        val mergedDeps = mutableMapOf<DependencyScanner.MavenData, MutableSet<DependencyScanner.MavenData>>()
        scriptDependencies.forEach { dep ->
            val deps = mergedDeps.getOrPut(DependencyScanner.MavenData(dep.group, dep.name, "")) { mutableSetOf() }
            deps.add(dep)
        }

        mergedDeps.forEach { (mergedDep, set) ->
            val latestVersion = getLatestVersionInfo(repositories, "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml")

            set.forEach { dep ->
                if (latestVersion != null) {
                    if (Version.from(dep.version) == latestVersion) {
                        latestVersionInfo.add(dep)
                    } else {
                        oldVersionInfo.add(Pair(dep, latestVersion))
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
            oldVersionInfo.forEach { (dep, ver) ->
                println("\t - ${dep.group} [${dep.version} -> $ver]")
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
            val latestVersion = getLatestVersionInfo(repositories, "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml")

            set.forEach { dep ->
                if (latestVersion != null) {
                    if (Version.from(dep.version) == latestVersion) {
                        latestVersionInfo.add(dep)
                    } else {
                        oldVersionInfo.add(Pair(dep, latestVersion))
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
            oldVersionInfo.forEach { (dep, ver) ->
                println("\t - ${dep.group}:${dep.name} [${dep.version} -> $ver]")
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
