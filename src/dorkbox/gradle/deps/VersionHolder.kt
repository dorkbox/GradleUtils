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

import dorkbox.version.Version

data class VersionHolder(var release: String?, val versions: MutableSet<String>) {
    var dirtyVersions = false

    fun updateReleaseVersion(version: String) {
        val curRelease = release
        if (curRelease == null) {
            release = version
        } else {
            // there can be errors when parsing version info, since not all version strings follow semantic versioning
            try {
                val currentVersion = Version(curRelease)
                val releaseVer = Version(version)

                if (releaseVer.greaterThan(currentVersion)) {
                    release = version
                }
            } catch (_: Exception) {
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
            val sorted = versions.sortedWith { o1, o2 ->
                Version(o1).compareWithBuildsTo(Version(o2))
            }

            val curVersion = Version(currentVersion)
            val withoutBuildInfo = sorted.filter {
                val version = Version(it)
                version.buildMetadata.isEmpty() &&
                version.preReleaseVersion.isEmpty() &&
                version.greaterThan(curVersion)
            }

            if (withoutBuildInfo.isEmpty()) {
                return versions.filter {
                    val version = Version(it)
                    version.greaterThan(curVersion)
                }
            }

            return withoutBuildInfo
        } catch (_: Exception) {
            // WHOOPS! There was an invalid version number! Instead of just crashing, try a different way...
            if (dirtyVersions) {
                // no idea, honestly... the list might not even be in order! Just return the entire thing and let the user sort it out
                return versions.toMutableList().apply { add(0, "Error parsing!") }.toList()
            } else {
                // fortunately for us, usually the maven order of version data is IN-ORDER, so we can "cheat" the system and look at
                // indexing instead
                val myVersionIndex = versions.indexOfFirst { it == currentVersion }
    //            println("INDEX: ${myVersionIndex}" )
                return if (myVersionIndex >= 0) {
                    versions.filterIndexed { index, _ -> index > myVersionIndex }
                } else {
                    versions.toMutableList().apply { add(0, "Error parsing!") }.toList()
                }
            }
        }
    }

    fun latestStableVersion(): String? {
        return versions.lastOrNull {
            try {
                val ver = Version(it)
                ver.buildMetadata.isEmpty() && ver.preReleaseVersion.isEmpty()
            } catch (_: Exception) {
                // not all version information is close to an actual version
                false
            }
        } ?: release
    }


    fun toVersionString(dep: DependencyScanner.Maven): String {
        val newestStableVersion = latestStableVersion()
        val spacer = if (newestStableVersion == dep.version) {
            "   "
        } else {
            "-> "
        }

        val limit = 3
        val possibleVersionChoices = getVersionOptions(dep.version).asReversed()
        return if (possibleVersionChoices.size > limit) {
            "$spacer${possibleVersionChoices.take(limit) + "..." + possibleVersionChoices.drop(limit).takeLast(limit)}".replace(", ...,", " ...")
        }
        else if (possibleVersionChoices.isNotEmpty()) {
            "$spacer$possibleVersionChoices"
        }
        else {
            spacer
        }
//      println("\t - ${dep.group}:${dep.version} -> ${versionHolder.versions}")
    }
}
