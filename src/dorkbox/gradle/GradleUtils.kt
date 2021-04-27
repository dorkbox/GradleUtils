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
package dorkbox.gradle

import dorkbox.gradle.deps.GetVersionInfoTask
import dorkbox.gradle.jpms.JavaXConfiguration
import org.gradle.api.DomainObjectCollection
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet


/**
 * For managing (what should be common sense) gradle tasks, such as:
 *  - updating gradle
 *  - updating dependencies
 *  - checking version requirements
 */
class GradleUtils : Plugin<Project> {
    private lateinit var propertyMappingExtension: StaticMethodsAndTools

    override fun apply(project: Project) {
        println("\t${project.name}: Gradle ${project.gradle.gradleVersion}, Java ${JavaVersion.current()}")

        propertyMappingExtension = project.extensions.create("GradleUtils", StaticMethodsAndTools::class.java, project)

        project.tasks.create("updateGradleWrapper", GradleUpdateTask::class.java).apply {
            group = "gradle"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Automatically update GRADLE to the latest version"
        }

        project.tasks.create("updateDependencies", GetVersionInfoTask::class.java).apply {
            group = "gradle"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Fetch the latest version information for project dependencies"
        }

        project.tasks.create("checkKotlin", CheckKotlinTask::class.java).apply {
            group = "other"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Debug output for checking if kotlin is currently installed in the project"
        }
    }
}

// Fix defaults for gradle, since it's mildly retarded when it comes to kotlin, so we can have sane sourceset/configuration options
// from: https://github.com/gradle/kotlin-dsl-samples/blob/201534f53d93660c273e09f768557220d33810a9/buildSrc/src/main/kotlin/build/KotlinPluginExtensions.kt
val SourceSet.kotlin: SourceDirectorySet
    get() =
        (this as org.gradle.api.internal.HasConvention)
                .convention
                .getPlugin(KotlinSourceSet::class.java)
                .kotlin

fun SourceSet.kotlin(action: SourceDirectorySet.() -> Unit) =
        kotlin.action()

