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

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.util.*


/**
 * For managing (what should be common sense) gradle tasks, such as:
 *  - updating gradle
 *  - updating dependencies
 *  - checking version requirements
 */
class GradleUtils : Plugin<Project> {
    private lateinit var propertyMappingExtension: StaticMethodsAndTools

    override fun apply(project: Project) {
        println("\tGradle ${project.gradle.gradleVersion} on Java ${JavaVersion.current()}")

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
    }

    @Synchronized
    fun getVersion(): String? {
        var version: String? = null

        // try to load from maven properties first
        try {
            val p = Properties()
            val `is` = javaClass.getResourceAsStream("/META-INF/maven/com.my.group/my-artefact/pom.properties")
            if (`is` != null) {
                p.load(`is`)
                version = p.getProperty("version", "")
            }
        } catch (e: Exception) {
            // ignore
        }
        return version
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

