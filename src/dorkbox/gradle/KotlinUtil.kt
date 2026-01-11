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

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

class KotlinUtil {
    companion object {
        const val defaultKotlinVersion = "2.2"


        fun getKotlinVersion(project: Project): String {
            return try {
                val version = project.getKotlinPluginVersion()

                // we ONLY care about the major.minor
                val secondDot = version.indexOf('.', version.indexOf('.') + 1)
                version.substring(0, secondDot)
            } catch (_: Exception) {
                // in case we cannot parse it from the plugin, provide a reasonable default (the latest stable)
                defaultKotlinVersion
            }
        }
    }
}
