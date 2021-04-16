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
import org.gradle.api.tasks.TaskAction
import java.io.InputStreamReader
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

open class
GetVersionInfoTask : DefaultTask() {
    private data class VersionHolder(var release: String?, val versions: MutableSet<String>) {
        fun updateReleaseVersion(version: String) {
            if (release == null) {
                release = version
            } else {
                // there can be errors when parsing version info, since not versions follow loose-semantic versioning
                try {
                    val currentVersion = Version.from(release)
                    val releaseVer = Version.from(version)

                    if (releaseVer.greaterThan(currentVersion)) {
                        release = version
                    }
                } catch (e: Exception) {
                    // ignored
                }
            }
        }

        fun addVersion(ver: String) {
            versions.add(ver)
        }

        fun getVersionOptions(currentVersion: String): List<String> {
            // there can be errors when parsing version info, since not versions follow loose-semantic versioning
            val myVersionIndex = versions.indexOfFirst { it == currentVersion }

            return if (myVersionIndex >= 0) {
                return versions.filterIndexed { index, _ -> index <= myVersionIndex }
            } else {
                versions.toList()
            }
        }
    }

    companion object {
        private val releaseMatcher = """^.*(<release>)(.*)(<\/release>)""".toRegex()
        private val versionMatcher = """^.*(<version>)(.*)(<\/version>)""".toRegex()

        private val httpDispatcher = Executors.newFixedThreadPool(8)

        private fun getLatestVersionInfo(
            repositories: List<String>,
            mergedDeps: MutableMap<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>,
        ): MutableMap<DependencyScanner.Maven, VersionHolder> {

            // first get all version information across ALL projects.
            // do this in parallel with coroutines!
            val futures = mutableListOf<Future<*>>()
            val mergedVersionInfo = mutableMapOf<DependencyScanner.Maven, VersionHolder>()
            val downloadLock = ReentrantReadWriteLock()

            // mergedDeps now has all deps for all projects. now we want to resolve (but only if we don't already have it)
            mergedDeps.forEach { (mergedDep, _) ->
                val metadataUrl = "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml"

                // version info is per dependency
                val depVersionInfo = downloadLock.write {
                    mergedVersionInfo.getOrPut(DependencyScanner.Maven(mergedDep.group, mergedDep.name)) {
                        VersionHolder(null, mutableSetOf())
                    }
                }

                val future = httpDispatcher.submit {
                    repositories.forEach { repoUrl ->
                        try {
                            val url = URL(repoUrl + metadataUrl)
                            // println("Trying: $url")
                            with(url.openConnection() as java.net.HttpURLConnection) {
                                InputStreamReader(inputStream).readLines().forEach { line ->
                                    var matchResult = releaseMatcher.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        downloadLock.write {
                                            depVersionInfo.updateReleaseVersion(ver)
                                        }
                                    }

                                    matchResult = versionMatcher.find(line)
                                    if (matchResult != null) {
                                        val (_, ver, _) = matchResult.destructured
                                        downloadLock.write {
                                            depVersionInfo.addVersion(ver)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }

                futures.add(future)
            }

            if (mergedDeps.isNotEmpty()) {
                println("\tGetting version data for ${mergedDeps.size} dependencies...")
                futures.forEach {
                    it.get()
                }
            }

            downloadLock.write {
                // println("SIZE: " + mergedVersionInfo)
                return mergedVersionInfo
            }
        }

        fun scriptDependencies(staticMethodsAndTools: StaticMethodsAndTools, project: Project) {
            val repositories = staticMethodsAndTools.getProjectBuildScriptRepositoryUrls(project)

            val latestVersionInfo = mutableListOf<DependencyScanner.Maven>()
            val oldVersionInfo = mutableListOf<Pair<DependencyScanner.Maven, VersionHolder>>()
            val unknownVersionInfo = mutableListOf<DependencyScanner.Maven>()

            val scriptDependencies = staticMethodsAndTools.resolveBuildScriptDependencies(project)

            // we can have MULTIPLE versions of a single dependency in use!!
            val mergedDeps = mutableMapOf<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>()
            scriptDependencies.forEach { dep ->
                val deps = mergedDeps.getOrPut(DependencyScanner.Maven(dep.group, dep.name, "")) { mutableSetOf() }
                deps.add(dep)
            }


            // for script dependencies, ALWAYS add the gradle plugin repo!
            // (we hardcode the value, this is not likely to change, but easy enough to fix if it does...)
            val newRepos = mutableSetOf<String>()
            newRepos.add("https://plugins.gradle.org/m2/")
            newRepos.addAll(repositories)


            val mergedVersionInfo = getLatestVersionInfo(newRepos.toList(), mergedDeps)
            mergedDeps.forEach { (mergedDep, existingVersions) ->
                val latestInfo: VersionHolder = mergedVersionInfo[mergedDep]!!
                existingVersions.forEach { dep ->
                    if (latestInfo.release == null) {
                        unknownVersionInfo.add(dep)
                    } else {
                        if (dep.version == latestInfo.release) {
                            latestVersionInfo.add(dep)
                        } else {
                            oldVersionInfo.add(Pair(dep, latestInfo))
                        }
                    }
                }
            }

            val hasLatest = latestVersionInfo.isNotEmpty()
            val hasOld = oldVersionInfo.isNotEmpty()
            val hasUnknown = unknownVersionInfo.isNotEmpty()

            if (hasLatest || hasOld || hasUnknown) {
                println("\t------------------------------------------------------------")

                if (hasLatest) {
                    println("\tThe following build script dependencies are using the latest release version:")
                    latestVersionInfo.forEach { dep ->
                        println("\t - ${dep.group}:${dep.version}")
                    }
                }

                if (hasOld) {
                    println()
                    println("\tThe following build script dependencies need updates:")

                    oldVersionInfo.forEach { (dep, versionHolder) ->
                        // list release version AND all other versions greater than my version
                        val versionOptions = versionHolder.getVersionOptions(dep.version)

                        // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                        println("\t - ${dep.group}:${dep.version} -> $versionOptions")
                    }
                }

                if (hasUnknown) {
                    println()
                    println("\tThe following build script dependencies have unknown updates:")
                    unknownVersionInfo.forEach { dep ->
                        // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                        println("\t - ${dep.group}:${dep.version}")
                    }
                }
            }
        }

        private fun projectDependencies(staticMethodsAndTools: StaticMethodsAndTools, project: Project) {
            val mergedDeps = mutableMapOf<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>()
            val mergedRepos = mutableSetOf<String>()

            project.allprojects.forEach { subProject ->
                staticMethodsAndTools.resolveAllDeclaredDependencies(subProject).forEach { dep ->
                    val deps = mergedDeps.getOrPut(DependencyScanner.Maven(dep.group, dep.name)) { mutableSetOf() }
                    deps.add(DependencyScanner.Maven(dep.group, dep.name, dep.version))
                }

                // also grab all the repos. There will be MORE repos than necessary, but this way we can collate all of our date in a
                // sane and orderly manner
                mergedRepos.addAll(staticMethodsAndTools.getProjectRepositoryUrls(subProject))
            }

            // first get all version information across ALL projects.
            val mergedVersionInfo = getLatestVersionInfo(mergedRepos.toList(), mergedDeps)


            val latestVersionInfo = mutableListOf<DependencyScanner.Maven>()
            val oldVersionInfo = mutableListOf<Pair<DependencyScanner.Maven, VersionHolder>>()
            val unknownVersionInfo = mutableListOf<DependencyScanner.Maven>()


            // now for project dependencies!
            project.allprojects.forEach { subProject ->
                mergedDeps.clear()
                latestVersionInfo.clear()
                oldVersionInfo.clear()
                unknownVersionInfo.clear()


                // look up each project version dep info (now that we have all dep info)

                staticMethodsAndTools.resolveAllDeclaredDependencies(subProject).forEach { dep ->
                    val deps = mergedDeps.getOrPut(DependencyScanner.Maven(dep.group, dep.name)) { mutableSetOf() }
                    deps.add(DependencyScanner.Maven(dep.group, dep.name, dep.version))
                }

                mergedDeps.forEach { (mergedDep, existingVersions) ->
                    val latestInfo: VersionHolder = mergedVersionInfo[mergedDep]!!
                    existingVersions.forEach { dep ->
                        if (latestInfo.release != null) {
                            if (dep.version == latestInfo.release) {
                                latestVersionInfo.add(dep)
                            } else {
                                oldVersionInfo.add(Pair(dep, latestInfo))
                            }
                        } else {
                            unknownVersionInfo.add(dep)
                        }
                    }
                }


                val projectName = if (subProject == project) {
                    ""
                } else {
                    " '${subProject.name}'"
                }


                val hasLatest = latestVersionInfo.isNotEmpty()
                val hasOld = oldVersionInfo.isNotEmpty()
                val hasUnknown = unknownVersionInfo.isNotEmpty()

                if (hasLatest || hasOld || hasUnknown) {
                    println("\t------------------------------------------------------------")

                    if (hasLatest) {
                        println("\tThe following project$projectName dependencies are using the latest release version:")
                        latestVersionInfo.forEach { dep ->
                            println("\t - ${dep.group}:${dep.name}:${dep.version}")
                        }
                    }


                    if (hasOld) {
                        println()
                        println("\tThe following project$projectName dependencies need updates:")

                        oldVersionInfo.forEach { (dep, versionHolder) ->
                            // list release version AND all other versions greater than my version
                            val possibleVersionChoices = versionHolder.getVersionOptions(dep.version)

                            println("\t - ${dep.group}:${dep.name}:${dep.version} -> ${versionHolder.release}")
                            println("\t - ${dep.group}:${dep.name}:${dep.version} -> ${versionHolder.versions}")
                            println("\t - ${dep.group}:${dep.name}:${dep.version} -> $possibleVersionChoices")
                        }
                    }

                    if (hasUnknown) {
                        println()
                        println("\tThe following project$projectName dependencies have unknown updates:")
                        unknownVersionInfo.forEach { dep ->
                            println("\t - ${dep.group}:${dep.name}:${dep.version}")
                        }
                    }
                }
            }
        }
    }

    @TaskAction
    fun run() {
        val staticMethodsAndTools = StaticMethodsAndTools(project)

        scriptDependencies(staticMethodsAndTools, project)

        println()
        println()
        println()

        projectDependencies(staticMethodsAndTools, project)
    }
}
