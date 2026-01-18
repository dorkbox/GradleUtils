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
package dorkbox.gradle

import dorkbox.gradle.GradleUpdateTask.Companion.updateGradleWrapper
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

open class GradleCheckTask : DefaultTask() {
    companion object {
        val releaseText: String by lazy {
            URI("https://services.gradle.org/versions/current").toURL().readText()
        }

        val foundGradleVersion: String? by lazy {
            JSONObject(releaseText)["version"] as String?
        }

        val currentVersion: String by lazy {
            GradleVersion.current().version
        }

        fun sha256(file: File): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            file.forEachBlock { bytes: ByteArray, bytesRead: Int ->
                md.update(bytes, 0, bytesRead)
            }
            return md.digest()
        }

        enum class GradleVersionStatus {
            NOT_FOUND,
            UP_TO_DATE,
            SHA_MISMATCH,
            NEW_VERSION_AVAILABLE
        }

        fun checkGradleVersions(project: Project): GradleVersionStatus {
            if (foundGradleVersion.isNullOrEmpty()) {
                println("\tUnable to detect New Gradle Version. Output json: $releaseText")
                return GradleVersionStatus.NOT_FOUND
            }

            // Print wrapper jar location and SHA256 after update
            val wrapperJar = project.file("gradle/wrapper/gradle-wrapper.jar")

            val sha256LocalHex: String
            if (wrapperJar.exists()) {
                val sha256Local = sha256(wrapperJar)
                sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
            } else {
                println("\tWrapper JAR location: ${wrapperJar.absolutePath}")
                println("\tWrapper JAR file not found!")
                sha256LocalHex = ""
            }

            println("\tLocal  v$currentVersion SHA256: '$sha256LocalHex'")


            val sha256SumCurrentRemote = URI("https://services.gradle.org/distributions/gradle-${currentVersion}-wrapper.jar.sha256").toURL().readText()

            println("\tRemote v$currentVersion SHA256: '$sha256SumCurrentRemote'")
            if (currentVersion != foundGradleVersion) {
                val sha256SumUpdatedRemote = URI("https://services.gradle.org/distributions/gradle-${foundGradleVersion}-wrapper.jar.sha256").toURL().readText()
                println("\tRemote v$foundGradleVersion SHA256: '$sha256SumUpdatedRemote'")
            }


            if (currentVersion == foundGradleVersion && sha256SumCurrentRemote == sha256LocalHex) {
                println("\tGradle is already latest version '$foundGradleVersion' and has valid SHA256")
                return GradleVersionStatus.UP_TO_DATE
            }

            if (sha256SumCurrentRemote != sha256LocalHex) {
                println("\tGradle v$currentVersion SHA256 sums do not match!")
                return GradleVersionStatus.SHA_MISMATCH
            }
            else {
                println("\tDetected new Gradle Version: '$foundGradleVersion'")
                return GradleVersionStatus.NEW_VERSION_AVAILABLE
            }
        }
    }

    @get:Internal
    val savedProject: Property<Project> = project.objects.property(Project::class.java)

    @TaskAction
    fun run() {
        val state = checkGradleVersions(savedProject.get())
        if (state == GradleVersionStatus.SHA_MISMATCH) {
            println("\tReinstalling Gradle Wrapper to v$currentVersion")
            updateGradleWrapper(savedProject.get(), currentVersion)
        }
    }
}
