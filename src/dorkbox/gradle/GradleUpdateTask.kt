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

import dorkbox.gradle.GradleCheckTask.Companion.GradleVersionStatus
import dorkbox.gradle.GradleCheckTask.Companion.checkGradleVersions
import dorkbox.gradle.GradleCheckTask.Companion.currentVersion
import dorkbox.gradle.GradleCheckTask.Companion.foundGradleVersion
import dorkbox.gradle.GradleCheckTask.Companion.releaseText
import dorkbox.gradle.GradleCheckTask.Companion.sha256
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.kotlin.dsl.register

open class GradleUpdateTask : DefaultTask() {
    companion object {
        fun updateGradleWrapper(project: Project, versionToUse: String) {
            val tasks = project.tasks
            var wrapper = tasks.findByName("wrapperUpdate")
            if (wrapper == null) {
                wrapper = tasks.register<Wrapper>("wrapperUpdate") {
                    group = "other"

                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }

                    gradleVersion = versionToUse
                    distributionUrl = distributionUrl.replace("bin", "all")
                    distributionType = Wrapper.DistributionType.ALL
                }.get()
            }

            wrapper.actions.first().execute(wrapper)

            wrapper.outputs.files.filter { it.exists() && it.name == "gradle-wrapper.jar" }.first().also { file ->
//                println("\tTASK: ${file.path} exists")
                val sha256Local = sha256(file)
                val sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
                println("\tUpdate $versionToUse SHA256: '$sha256LocalHex'")
            }
        }
    }

    @get:Internal
    val savedProject: Property<Project> = project.objects.property(Project::class.java)

    @TaskAction
    fun run() {
        if (foundGradleVersion.isNullOrEmpty()) {
            println("\tUnable to detect New Gradle Version. Output json: $releaseText")
            return
        }

        val state = checkGradleVersions(savedProject.get())
        if (state == GradleVersionStatus.UP_TO_DATE || state == GradleVersionStatus.NOT_FOUND) {
            return
        }

        val versionToUse = if (state == GradleVersionStatus.SHA_MISMATCH) {
            println("\tReinstalling version $currentVersion")
            currentVersion
        }
        else {
            println("\tUpdating Gradle Wrapper to v$foundGradleVersion")
            foundGradleVersion!!
        }

        updateGradleWrapper(savedProject.get(), versionToUse)
    }
}
