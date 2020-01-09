/*
 * Copyright 2018 dorkbox, llc
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

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.api.Plugin
import org.gradle.api.Project


/**
 * For managing (what should be common sense) gradle tasks, such as:
 *  - updating gradle
 *  - updating dependencies
 *  - checking version requirements
 */
class GradleUtils : Plugin<Project> {
    private lateinit var propertyMappingExtension: StaticMethodsAndTools

    override fun apply(project: Project) {
        propertyMappingExtension = project.extensions.create("GradleUtils", StaticMethodsAndTools::class.java, project)

        project.tasks.create("autoUpdateGradle", GradleUpdateTask::class.java).apply {
            group = "gradle"
        }

        project.tasks.create("updateDependencies", DependencyUpdatesTask::class.java).apply {
            group = "gradle"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }

            resolutionStrategy { strategy ->
                strategy.componentSelection { rules ->
                    rules.all { component ->
                        val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview")
                                .map { qualifier -> Regex("(?i).*[.-]$qualifier[.\\d-]*") }
                                .any { regex -> regex.matches(component.candidate.version) }

                        if (rejected) {
                            component.reject("Release candidate")
                        }
                    }
                }
            }

            // optional parameters
            checkForGradleUpdate = false
        }
    }
}
