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

package dorkbox.gradle.deps

import com.dorkbox.version.Version
import dorkbox.gradle.StaticMethodsAndTools
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

open class
GetVersionInfoTask : DefaultTask() {
    private data class VersionHolder(var release: String?, val versions: MutableSet<String>) {
        var dirtyVersions = false

        fun updateReleaseVersion(version: String) {
            if (release == null) {
                release = version
            } else {
                // there can be errors when parsing version info, since not all version strings follow semantic versioning
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
            if (!versions.contains(ver)) {
                versions.add(ver)
            } else {
                dirtyVersions = true
            }
        }

        fun getVersionOptions(currentVersion: String): List<String> {
            // there can be errors when parsing version info, since not all version strings follow semantic versioning
            // first! try version sorting. This may fail!

            // there are no duplicates in this list
            try {
                // this creates a LOT of version objects. Probably better to store these in a list, however we want all backing data
                // structures to be strings.
                val curVersion = Version.from(currentVersion)
                return versions.sortedWith { o1, o2 ->
                    Version.from(o1).compareTo(Version.from(o2))
                }.filter {
                    Version.from(it).greaterThan(curVersion)
                }.toList()
            } catch (e: Exception) {
                // WHOOPS! There was an invalid version number! Instead of just crashing, try a different way...
                if (dirtyVersions) {
                    // no idea, honestly... the list might not even be in order! Just return the entire thing and let the user sort it out
                    return versions.toMutableList().apply { add(0, "Error parsing!") }.toList()
                } else {
                    // fortunately for us, the usually the maven order of version data is IN-ORDER, so we can "cheat" the system and look at
                    // indexing instead
                    val myVersionIndex = versions.indexOfFirst { it == currentVersion }
        //            println("INDEX: ${myVersionIndex}" )
                    return if (myVersionIndex >= 0) {
                        return versions.filterIndexed { index, _ -> index > myVersionIndex }
                    } else {
                        versions.toMutableList().apply { add(0, "Error parsing!") }.toList()
                    }
                }
            }
        }
    }

    companion object {
        private val releaseMatcher = """(<release>)(.*)(<\/release>)""".toRegex()

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
                            val url = URL(repoUrl + metadataUrl)
                            // println("Trying: $url")

                            with(url.openConnection() as java.net.HttpURLConnection) {
                                var inVersioningSection = false
                                InputStreamReader(inputStream).readLines().forEach { line ->
                                    val trimmed = line.trim()

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
//                                        println("Release: ${mergedDep.group}:${mergedDep.name} $ver")
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
//                                                    println("Version: ${mergedDep.group}:${mergedDep.name} $ver")
                                                    mergedVersionInfo[mavenIdKey]!!.addVersion(ver)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (ignored: Exception) {
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
            val (futures, versionHolders) = getLatestVersionInfo(listOf("https://plugins.gradle.org/m2/"), mergedDeps, forGradleScripts = true)
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
                        val possibleVersionChoices = versionHolder.getVersionOptions(dep.version)
                        if (possibleVersionChoices.size > 1) {
                            // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                            println("\t - ${dep.group}:${dep.version} -> ${versionHolder.release}  $possibleVersionChoices")
                        } else {
                            // BUILD SCRIPT DEPS HAVE FUNNY NOTATION!
                            println("\t - ${dep.group}:${dep.version} -> ${versionHolder.release}")
                        }
//                        println("\t - ${dep.group}:${dep.version} -> ${versionHolder.versions}")
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
                            val possibleVersionChoices = versionHolder.getVersionOptions(dep.version)
                            if (possibleVersionChoices.size > 1) {
                                println("\t - ${dep.group}:${dep.name}:${dep.version} -> ${versionHolder.release}  $possibleVersionChoices")
                            } else {
                                println("\t - ${dep.group}:${dep.name}:${dep.version} -> ${versionHolder.release}")
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
