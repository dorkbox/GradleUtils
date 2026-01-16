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
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import org.json.JSONObject
import java.net.URI

open class
GradleCheckTask : DefaultTask() {
    companion object {
        val releaseText: String by lazy {
            URI("https://services.gradle.org/versions/current").toURL().readText()
        }

        val foundGradleVersion: String? by lazy {
            JSONObject(releaseText)["version"] as String?
        }
    }

    @TaskAction
    fun run() {
        if (foundGradleVersion.isNullOrEmpty()) {
            println("\tUnable to detect New Gradle Version. Output json: $releaseText")
        }
        else {
            val current = GradleVersion.current().version

            println("\tDetected new Gradle Version: '$foundGradleVersion', Current version: '$current'")
        }
    }
}
