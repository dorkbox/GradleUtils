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

import dorkbox.gradle.buildScriptRepositoryUrls
import dorkbox.gradle.getProjectRepositoryUrls
import dorkbox.gradle.resolveAllDeclaredDependencies
import dorkbox.gradle.resolveBuildScriptDependencies
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.locks.*
import kotlin.concurrent.write

abstract class GetVersionInfoTask : DefaultTask() {
    companion object {
        private val releaseMatcher = """(<release>)(.*)(</release>)""".toRegex()
        private const val GRADLE_PLUGIN_REPO = "https://plugins.gradle.org/m2/"

        private val httpDispatcher = Executors.newFixedThreadPool(8)

        private fun getLatestVersionInfo(
            repositories: List<String>,
            mergedDeps: MutableMap<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>,
            mergedVersionInfo: MutableMap<DependencyScanner.Maven, VersionHolder> = mutableMapOf(),
            forGradleScripts: Boolean = false,
        ): Pair<MutableList<Future<*>>, MutableMap<DependencyScanner.Maven, VersionHolder>> {

            // first get all version information across ALL projects.
            // do this in parallel with coroutines!
            val futures = mutableListOf<Future<*>>()
            val downloadLock = ReentrantReadWriteLock()

            // mergedDeps now has all deps for all projects. now we want to resolve (but only if we don't already have it)
            mergedDeps.forEach { (mergedDep, _) ->
                val metadataUrl = if (forGradleScripts)
                    // we also have to ADD a prefix to the group ID, because a gradle plugin is **SLIGHTLY** different in how it works.
                    "gradle/plugin/${mergedDep.group.replace(".", "/")}/maven-metadata.xml"
                else
                    "${mergedDep.group.replace(".", "/")}/${mergedDep.name}/maven-metadata.xml"


                val mavenIdKey = DependencyScanner.Maven(mergedDep.group, mergedDep.name)

                // version info is per dependency
                downloadLock.write {
                    mergedVersionInfo.getOrPut(mavenIdKey) {
                        VersionHolder(null, mutableSetOf())
                    }
                }

                val future = httpDispatcher.submit {
                    repositories.forEach { repoUrl ->
                        try {
                            val url = URI(repoUrl + metadataUrl).toURL()
//println("URL: $url")

                            with(url.openConnection() as java.net.HttpURLConnection) {
                                var inVersioningSection = false
                                val inputStreamReader = InputStreamReader(inputStream)
                                inputStreamReader.readLines().forEach { line ->
                                    val trimmed = line.trim()
//println("Reading: $line")
                                    if (!inVersioningSection) {
                                        if (trimmed == "<versioning>") {
                                            inVersioningSection = true
                                        }

                                    } else {
                                        if (trimmed == "</versioning>") {
                                            inVersioningSection = false
                                        }

                                        // only match version info when we are in the "<versioning>" section
                                        val matchResult = releaseMatcher.find(trimmed)
                                        if (matchResult != null) {
                                            val (_, ver, _) = matchResult.destructured
//println("Release: ${mergedDep.group}:${mergedDep.name} $ver")
                                            downloadLock.write {
                                                mergedVersionInfo[mavenIdKey]!!.updateReleaseVersion(ver)
                                            }
                                        }

                                        // not using regex, because this becomes complex.
                                        // There can be a SINGLE version per line, or MULTIPLE versions per line.
                                        // This handles both cases.
                                        if (trimmed.startsWith("<version>")) {
                                            // list out 1 or more versions
                                            val versions = trimmed.split("<version>").filter {it.isNotEmpty()}
                                                .map { it.substring(0, it.indexOf('<')) }

                                            downloadLock.write {
                                                versions.forEach { ver ->
//println("Version: ${mergedDep.group}:${mergedDep.name} $ver")
                                                    mergedVersionInfo[mavenIdKey]!!.addVersion(ver)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                futures.add(future)
            }

            downloadLock.write {
//                mergedVersionInfo.forEach { t, u ->
//                    println("$t :: ${u.versions}")
//                }

                return Pair(futures, mergedVersionInfo)
            }
        }

        fun scriptDependencies(project: Project) {
            val repositories = project.buildScriptRepositoryUrls()
            val scriptDependencies = project.resolveBuildScriptDependencies()


            val latestVersionInfo = mutableListOf<DependencyScanner.Maven>()
            val oldVersionInfo = mutableListOf<Pair<DependencyScanner.Maven, VersionHolder>>()
            val unknownVersionInfo = mutableListOf<DependencyScanner.Maven>()


            // we can have MULTIPLE versions of a single dependency in use!!
            val mergedDeps = mutableMapOf<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>()
            scriptDependencies.forEach { dep ->
                val deps = mergedDeps.getOrPut(DependencyScanner.Maven(dep.group, dep.name, "")) { mutableSetOf() }
                deps.add(dep)
            }

            // for script dependencies, ALWAYS add the gradle plugin repo!
            // (we hardcode the value, this is not likely to change, but easy enough to fix if it does...)
            val (futures, versionHolders) = getLatestVersionInfo(listOf(GRADLE_PLUGIN_REPO), mergedDeps, forGradleScripts = true)
            val (futures2, _) = getLatestVersionInfo(repositories, mergedDeps, versionHolders)

            if (mergedDeps.isNotEmpty()) {
                // suppress duplicate messages when initially parsing gradle scripts (since it's a redundant message )
                println("\tGetting version data for ${mergedDeps.size} dependencies...")
            }

            (futures + futures2).forEach {
                // wait for all of them to finish
                it.get()
            }

//            versionHolders.forEach { (t, u) ->
//                println("$t :: ${u.versions}")
//            }

            mergedDeps.forEach { (mergedDep, existingVersions) ->
                val versionHolder: VersionHolder = versionHolders[mergedDep]!!

                existingVersions.forEach { dep ->
                    if (versionHolder.release == null) {
                        unknownVersionInfo.add(dep)
                    } else {
                        if (dep.version == versionHolder.release) {
                            latestVersionInfo.add(dep)
                        } else {
                            oldVersionInfo.add(Pair(dep, versionHolder))
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
                    if (hasLatest) {
                        println()
                    }
                    println("\tThe following build script dependencies need updates:")

                    oldVersionInfo.forEach { (dep, versionHolder) ->
                        // list release version AND all other versions greater than my version

                        // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                        println("\t - ${dep.group}:${dep.version} ${versionHolder.toVersionString(dep)}")
                    }
                }

                if (hasUnknown) {
                    if (hasLatest || hasOld) {
                        println()
                    }
                    println("\tThe following build script dependencies have unknown updates:")
                    unknownVersionInfo.forEach { dep ->
                        // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                        println("\t - ${dep.group}:${dep.version}")
                    }
                }
            }
        }

        private fun projectDependencies(project: Project) {
            val allprojects = project.allprojects

            val mergedDeps = mutableMapOf<DependencyScanner.Maven, MutableSet<DependencyScanner.Maven>>()
            val mergedRepos = mutableSetOf<String>()


            allprojects.forEach { subProject ->
                subProject.resolveAllDeclaredDependencies().forEach { dep ->
                    val deps = mergedDeps.getOrPut(DependencyScanner.Maven(dep.group, dep.name)) { mutableSetOf() }
                    deps.add(DependencyScanner.Maven(dep.group, dep.name, dep.version))
                }

                // also grab all the repos. There will be MORE repos than necessary, but this way we can collate all of our date in a
                // sane and orderly manner
                mergedRepos.addAll(subProject.getProjectRepositoryUrls())
            }


            // first get all version information across ALL projects.
            val (futures, versionHolders) = getLatestVersionInfo(mergedRepos.toList(), mergedDeps)
            if (mergedDeps.isNotEmpty()) {
                // suppress duplicate messages when initially parsing gradle scripts (since it's a redundant message )
                println("\tGetting version data for ${mergedDeps.size} dependencies...")
            }

            futures.forEach {
                // wait for all of them to finish
                it.get()
            }



            val latestVersionInfo = mutableListOf<DependencyScanner.Maven>()
            val oldVersionInfo = mutableListOf<Pair<DependencyScanner.Maven, VersionHolder>>()
            val unknownVersionInfo = mutableListOf<DependencyScanner.Maven>()


            // now for project dependencies!
            allprojects.forEach { subProject ->
                mergedDeps.clear()
                latestVersionInfo.clear()
                oldVersionInfo.clear()
                unknownVersionInfo.clear()


                // look up each project version dep info (now that we have all dep info)

                subProject.resolveAllDeclaredDependencies().forEach { dep ->
                    val deps = mergedDeps.getOrPut(DependencyScanner.Maven(dep.group, dep.name)) { mutableSetOf() }
                    deps.add(DependencyScanner.Maven(dep.group, dep.name, dep.version))
                }

                mergedDeps.forEach { (mergedDep, existingVersions) ->
                    val versionHolder: VersionHolder = versionHolders[mergedDep]!!

                    existingVersions.forEach { dep ->
                        if (versionHolder.release != null) {
                            if (dep.version == versionHolder.release) {
                                latestVersionInfo.add(dep)
                            } else {
                                oldVersionInfo.add(Pair(dep, versionHolder))
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
                        if (hasLatest) {
                            println()
                        }
                        println("\tThe following project$projectName dependencies need updates:")

                        oldVersionInfo.forEach { (dep, versionHolder) ->
                            // list release version AND all other versions greater than my version
                            println("\t - ${dep.group}:${dep.name}:${dep.version} ${versionHolder.toVersionString(dep)}")
                        }
                    }

                    if (hasUnknown) {
                        if (hasLatest || hasOld) {
                            println()
                        }
                        println("\tThe following project$projectName dependencies have unknown updates:")
                        unknownVersionInfo.forEach { dep ->
                            println("\t - ${dep.group}:${dep.name}:${dep.version}")
                        }
                    }
                }
            }
        }
    }

    @get:Internal
    abstract val savedProject: Property<Project>

    @TaskAction
    fun run() {
        scriptDependencies(savedProject.get())

        println()
        println()
        println()

        projectDependencies(savedProject.get())
    }
}
