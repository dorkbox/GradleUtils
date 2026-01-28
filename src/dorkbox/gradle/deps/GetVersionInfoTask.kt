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
import dorkbox.version.Version
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
            mergedVersionInfo: MutableMap<DependencyScanner.Maven, VersionHolder>,
            forGradleScripts: Boolean = false,
        ) {
            val debug = false

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
                            if (debug) {
                                println("URL: $url")
                            }

                            with(url.openConnection() as java.net.HttpURLConnection) {
                                var inVersioningSection = false
                                val inputStreamReader = InputStreamReader(inputStream)
                                inputStreamReader.readLines().forEach { line ->
                                    val trimmed = line.trim()
                                    if (debug) {
                                        println("Reading: $trimmed")
                                    }

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
                                            if (debug) {
                                                println("\tRelease: ${mergedDep.group}:${mergedDep.name}:$ver")
                                            }
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
                                                    if (debug) {
                                                        println("\tVersion: ${mergedDep.group}:${mergedDep.name}:$ver")
                                                    }
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

            // wait for all the downloading to complete
            futures.forEach {
                it.get()
            }

            if (debug) {
                downloadLock.write {
                    mergedVersionInfo.forEach { (t, u) ->
                        println("$t :: ${u.versions}")
                    }
                }
            }
        }

        private fun List<Pair<DependencyScanner.Maven, VersionHolder>>.filterStable(): List<Pair<DependencyScanner.Maven, VersionHolder>> {
            return filter { (dep, versionHolder) ->
                versionHolder.release != null
            }.filter { (dep, versionHolder) ->
                val newestStableVersion = versionHolder.versions.lastOrNull {
                    val ver = Version(it)
                    ver.buildMetadata.isEmpty() && ver.preReleaseVersion.isEmpty()
                } ?: versionHolder.release

                dep.version == newestStableVersion
            }.distinctBy { (dep, _) ->
                // Remove duplicates based on group:name:version
                "${dep.group}:${dep.name}:${dep.version}"
            }
        }


        private fun List<Pair<DependencyScanner.Maven, VersionHolder>>.printAlignedText(
            key: String,
            function: (DependencyScanner.Maven, VersionHolder) -> String
        ) {
            val text = this.map { (dep, versionHolder) ->
                function(dep, versionHolder)
            }

            val max = text.maxOfOrNull { s ->
                s.indexOf(key, 0)
            } ?: 0

            text.forEach { s ->
                val index = s.indexOf(key, 0)
                var spacer = ""
                var offset = max - index
                while (offset > 0) {
                    offset--
                    spacer += " "
                }
                println(s.replace(key, "$spacer$key"))
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

            val versionHolders = mutableMapOf<DependencyScanner.Maven, VersionHolder>()

            // for script dependencies, ALWAYS add the gradle plugin repo!
            // (we hardcode the value, this is not likely to change, but easy enough to fix if it does...)
            val futures = getLatestVersionInfo(listOf(GRADLE_PLUGIN_REPO), mergedDeps, versionHolders, forGradleScripts = true)
            val futures2 = getLatestVersionInfo(repositories, mergedDeps, versionHolders)


            if (mergedDeps.isNotEmpty()) {
                // suppress duplicate messages when initially parsing gradle scripts (since it's a redundant message )
                println("\tGetting version data for ${mergedDeps.size} dependencies...")
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
                            if (!dep.group.startsWith("org.jetbrains")) {
                                oldVersionInfo.add(Pair(dep, versionHolder))
                            }
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

                    val oldStableVersion = oldVersionInfo.filterStable()

                    val actuallyOld = oldVersionInfo.minus(oldStableVersion.toSet())
                    if (actuallyOld.isNotEmpty()) {
                        println("\tThe following build script dependencies need updates:")

                        actuallyOld.printAlignedText("[") { dep, versionHolder ->
                            // list release version AND all other versions greater than my version
                            "\t - ${dep.group}:${dep.version} ${versionHolder.toVersionString(dep)}"
                        }
                    }


                    if (oldStableVersion.isNotEmpty()) {
                        if (actuallyOld.isNotEmpty()) {
                            println()
                        }
                        println("\tThe following build script dependencies have risky updates:")

                        // now align text
                        oldStableVersion.printAlignedText("[") { dep, versionHolder ->
                            "\t - ${dep.group}:${dep.version} ${versionHolder.toVersionString(dep)}"
                        }
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

            val versionHolders = mutableMapOf<DependencyScanner.Maven, VersionHolder>()

            // first get all version information across ALL projects.
            getLatestVersionInfo(mergedRepos.toList(), mergedDeps, versionHolders)

            if (mergedDeps.isNotEmpty()) {
                // suppress duplicate messages when initially parsing gradle scripts (since it's a redundant message )
                println("\tGetting version data for ${mergedDeps.size} dependencies...")
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

                        val oldStableVersion = oldVersionInfo.filterStable()

                        val actuallyOld = oldVersionInfo.minus(oldStableVersion)
                        if (actuallyOld.isNotEmpty()) {
                            println("\tThe following project$projectName dependencies need updates:")

                            actuallyOld.printAlignedText("[") { dep, versionHolder ->
                                // list release version AND all other versions greater than my version
                                "\t - ${dep.group}:${dep.name}:${dep.version} ${versionHolder.toVersionString(dep)}"
                            }
                        }



                        if (oldStableVersion.isNotEmpty()) {
                            if (actuallyOld.isNotEmpty()) {
                                println()
                            }
                            println("\tThe following project$projectName dependencies have risky updates:")

                            // now align text
                            oldStableVersion.printAlignedText("[") { dep, versionHolder ->
                                "\t - ${dep.group}:${dep.version} ${versionHolder.toVersionString(dep)}"
                            }
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
        val get = savedProject.get()
        scriptDependencies(get)

        println()
        println()
        println()

        projectDependencies(get)
    }
}
