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
package dorkbox.gradle.wrapper

import org.gradle.api.tasks.TaskAction

abstract class GradleUpdateTask : Wrapper() {
    init {
        group = "gradle"
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        description = "Automatically update Gradle to the latest version"
    }

    @TaskAction
    override fun generate() {
        if (remoteCurrentGradleVersion.isNullOrEmpty()) {
            println("\tUnable to detect New Gradle Version. Output json: $releaseText")
            return
        }

        val state = checkGradleVersions()
        if (state == Companion.Status.UP_TO_DATE || state == Companion.Status.NOT_FOUND) {
            return
        }

        println("\tUpdating Gradle Wrapper to v${remoteCurrentGradleVersion}")

        gradleVersion = remoteCurrentGradleVersion!!

        super.generate()

        val sha256Local = sha256(jarFile)
        val sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
        println("\tUpdate $gradleVersion SHA256: '$sha256LocalHex'")
    }
}
