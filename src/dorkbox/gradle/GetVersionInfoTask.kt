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
import kotlinx.coroutines.*
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import java.io.InputStreamReader
import java.net.URL
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

open class
GetVersionInfoTask : DefaultTask() {
    private data class VersionHolder(var release: Version?, val versions: MutableList<Version>) {
        fun updateReleaseVersion(version: String) {
            val releaseVer = Version.from(version)

            if (release == null) {
                release = releaseVer
            } else if (releaseVer.greaterThan(release)) {
                release = releaseVer
            }
        }

        fun addVersion(ver: String) {
            versions.add(Version.from(ver))
        }
    }

    companion object {
        private val releaseMatcher = """^.*(<release>)(.*)(<\/release>)""".toRegex()
        private val versionMatcher = """^.*(<version>)(.*)(<\/version>)""".toRegex()

        private val httpDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

        private fun getLatestVersionInfo(
            repositories: List<String>,
            mergedDeps: MutableMap<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>,
        ): MutableMap<DependencyScanner.Maven, VersionHolder> {

            // first get all version information across ALL projects.
            // do this in parallel with coroutines!
            val jobs = mutableListOf<Job>()
            val mergedVersionInfo = mutableMapOf<DependencyScanner.Maven, VersionHolder>()
            val downloadLock = ReentrantReadWriteLock()

            // mergedDeps now has all deps for all projects. now we want to resolve (but only if we don't already have it)
            mergedDeps.forEach { (mergedDep, _) ->
                val metadataUrl = "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml"

                // version info is per dependency
                val depVersionInfo = downloadLock.write {
                    mergedVersionInfo.getOrPut(DependencyScanner.Maven(mergedDep.group, mergedDep.name)) {
                        VersionHolder(null, mutableListOf())
                    }
                }

                val job = GlobalScope.launch {
                    withContext(httpDispatcher) {
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
                }
                jobs.add(job)
            }

            println("\tGetting version data for ${mergedDeps.size} dependencies...")
            runBlocking {
                jobs.forEach { it.join() }
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
                    if (latestInfo.release != null) {
                        if (Version.from(dep.version) == latestInfo.release) {
                            latestVersionInfo.add(dep)
                        } else {
                            oldVersionInfo.add(Pair(dep, latestInfo))
                        }
                    } else {
                        unknownVersionInfo.add(dep)
                    }
                }
            }

            println("\t------------------------------------------------------------")
            println("\tThe following build script dependencies are using the latest release version:")
            latestVersionInfo.forEach { dep ->
                println("\t - ${dep.group}:${dep.version}")
            }

            if (oldVersionInfo.isNotEmpty()) {
                println()
                println("\tThe following build script dependencies need updates:")

                oldVersionInfo.forEach { (dep, versionHolder) ->
                    // list release version AND all other versions greater than my version
                    val depVersion = Version.from(dep.version)
                    val possibleVersionChoices = versionHolder.versions.filter { it.greaterThan(depVersion) }.toSet()

                    // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                    println("\t - ${dep.group} [${dep.version} -> ${versionHolder.release}]")
                    if (possibleVersionChoices.size > 1) {
                        println("\t\t$possibleVersionChoices")
                    }
                }
            }

            if (unknownVersionInfo.isNotEmpty()) {
                println()
                println("\tThe following build script dependencies have unknown updates:")
                unknownVersionInfo.forEach { dep ->
                    // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                    println("\t - ${dep.group}:${dep.version}")
                }
            }
        }

        private fun projectDependencies(staticMethodsAndTools: StaticMethodsAndTools, project: Project) {
            // collect ALL projected + children projects.
            val projects = mutableListOf<Project>()
            val recursive = LinkedList<Project>()
            recursive.add(project)


            var next: Project
            while (recursive.isNotEmpty()) {
                next = recursive.poll()
                projects.add(next)
                recursive.addAll(next.childProjects.values)
            }


            val mergedDeps = mutableMapOf<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>()
            val mergedRepos = mutableSetOf<String>()


            projects.forEach { subProject ->
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
            projects.forEach { subProject ->
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
                            if (Version.from(dep.version) == latestInfo.release) {
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


                println("\t------------------------------------------------------------")
                println("\tThe following project$projectName dependencies are using the latest release version:")
                latestVersionInfo.forEach { dep ->
                    println("\t - ${dep.group}:${dep.name}:${dep.version}")
                }

                if (oldVersionInfo.isNotEmpty()) {
                    println()
                    println("\tThe following project$projectName dependencies need updates:")

                    oldVersionInfo.forEach { (dep, versionHolder) ->
                        // list release version AND all other versions greater than my version
                        val depVersion = Version.from(dep.version)
                        val possibleVersionChoices = versionHolder.versions.filter { it.greaterThan(depVersion) }.toSet()

                        println("\t - ${dep.group}:${dep.name} [${dep.version} -> ${versionHolder.release}]")
                        if (possibleVersionChoices.size > 1) {
                            println("\t\t$possibleVersionChoices")
                        }
                    }
                }

                if (unknownVersionInfo.isNotEmpty()) {
                    println()
                    println("\tThe following project$projectName dependencies have unknown updates:")
                    unknownVersionInfo.forEach { dep ->
                        println("\t - ${dep.group}:${dep.name}:${dep.version}")
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
