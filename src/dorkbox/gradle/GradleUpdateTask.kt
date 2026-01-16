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

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.kotlin.dsl.register
import org.gradle.util.GradleVersion
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

abstract class
GradleUpdateTask : DefaultTask() {
    companion object {
        val releaseText: String by lazy {
            URI("https://services.gradle.org/versions/current").toURL().readText()
        }

        val foundGradleVersion: String? by lazy {
            JSONObject(releaseText)["version"] as String?
        }

        val sha256SumOnline: String by lazy {
            URI("https://services.gradle.org/distributions/gradle-${foundGradleVersion}-wrapper.jar.sha256").toURL().readText()
        }

        private fun sha256(file: File): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            file.forEachBlock { bytes: ByteArray, bytesRead: Int ->
                md.update(bytes, 0, bytesRead)
            }
            return md.digest()
        }
    }

    @get:Internal
    abstract val savedProject: Property<Project>

    @TaskAction
    fun run() {
        if (foundGradleVersion.isNullOrEmpty()) {
            println("\tUnable to detect New Gradle Version. Output json: $releaseText")
        }
        else {
            val current = GradleVersion.current().version

            val sha256SumLocal = URI("https://services.gradle.org/distributions/gradle-${foundGradleVersion}-wrapper.jar.sha256").toURL().readText()

            // Print wrapper jar location and SHA256 after update
            val wrapperJar = project.file("gradle/wrapper/gradle-wrapper.jar")
            println("\tWrapper JAR location: ${wrapperJar.absolutePath}")

            if (wrapperJar.exists()) {
                val sha256Local = sha256(wrapperJar)
                val sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
                println("\tUpdated SHA256 sum is '$sha256LocalHex'")
            } else {
                println("\tWrapper JAR file not found after update!")
            }


//            if (current == foundGradleVersion) {
                println("\tGradle is already latest version '$foundGradleVersion'")


                println("\tOnline  SHA256 sum is '$sha256SumOnline'")
                println("\tCurrent SHA256 sum is '$sha256SumLocal'")

//            } else {
                println("\tDetected new Gradle Version: '$foundGradleVersion', updating from '$current'")

            val tasks = savedProject.get().tasks


            val wrapper = tasks.findByName("wrapperUpdate") ?: tasks.register<Wrapper>("wrapperUpdate")
            tasks.register<Wrapper>("wrapperUpdate") {
                    group = "other"

                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }

                    outputs.files.forEach {
                        println("${it.path}")
                    }

                    gradleVersion = foundGradleVersion
                    distributionUrl = distributionUrl.replace("bin", "all")
                    distributionType = DistributionType.ALL

                    doLast {
                        outputs.files.filter { it.exists() && it.nameWithoutExtension == "gradle-wrapper" }.first().also { file ->
                            println("${file.path} exists")

                            val sha256Local = sha256(file)
                            val sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
                            println("\tUpdated SHA256 sum is '$sha256LocalHex'")
                        }
                    }
                }.get().also {
                    actions.first().execute(this)
                }


//            }
        }
    }
}
