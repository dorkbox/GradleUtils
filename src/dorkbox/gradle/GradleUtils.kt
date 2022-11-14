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
import org.gradle.api.*
import org.gradle.api.file.SourceDirectorySet
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.*
import java.net.URL
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream


/**
 * For managing (what should be common sense) gradle tasks, such as:
 *  - updating gradle
 *  - updating dependencies
 *  - checking version requirements
 */
class GradleUtils : Plugin<Project> {
    private lateinit var propertyMappingExtension: StaticMethodsAndTools

    init {
        // Disable JarURLConnections caching
        URL("jar:file://dummy.jar!/").openConnection().defaultUseCaches = false
    }

    override fun apply(project: Project) {
        val current = GradleVersion.current()
        if (current < GradleVersion.version("7.0")) {
            // we require v7+ of gradle to use this version of the util project.
            throw GradleException("${project.name}: Gradle ${project.gradle.gradleVersion} requires Gradle 7.0+ to continue.")
        }


        println("\t${project.name}: Gradle ${project.gradle.gradleVersion}, Java ${JavaVersion.current()}")

        propertyMappingExtension = project.extensions.create("GradleUtils", StaticMethodsAndTools::class.java, project)

        project.tasks.create("updateGradleWrapper", GradleUpdateTask::class.java).apply {
            group = "gradle"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Automatically update Gradle to the latest version"
        }

        project.tasks.create("checkGradleVersion", GradleCheckTask::class.java).apply {
            group = "gradle"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Gets both the latest and currently installed Gradle versions"
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

fun org.gradle.api.Project.prepLibraries(): PrepLibrariesTask {
    return this.tasks.maybeCreate("prepareLibraries", PrepLibrariesTask::class.java)
}

fun org.gradle.api.Project.getDependenciesAsClasspath(): String {
    return this.prepLibraries().getAsClasspath()
}

fun org.gradle.api.Project.getAllLibraries(): Map<File, String> {
    return PrepLibrariesTask.collectLibraries(this.rootProject.allprojects.toTypedArray())
}

fun org.gradle.api.Project.copyLibrariesTo(location: File) {
    return this.project.prepLibraries().copyLibrariesTo(location)
}

fun org.gradle.api.Project.copyAllLibrariesTo(location: File) {
    return this.project.prepLibraries().copyLibrariesTo(location)
}

fun KotlinSourceSet.kotlin(action: SourceDirectorySet.() -> Unit) {
    this.kotlin.apply {
        action(this)
    }
}

/**
 * Adds the specified files AS REGULAR FILES to the jar. NOTE: This is done in-memory...
 *
 * NOTE: The DEST path MUST BE IN UNIX FORMAT! If the path is windows format, it will BREAK in unforseen ways!
 *
 * @param filesToAdd First in pair is SOURCE file, second in pair is DEST (where the file goes in the jar) Directories are not included!
 */
@Throws(IOException::class)
fun JarFile.addFilesToJar(filesToAdd: List<Pair<File, String>>) {
    var entry: JarEntry

    // we have to use a JarFile, so we preserve the comments that might already be in the file.
    this.use { jarFile ->
        val byteArrayOutputStream = ByteArrayOutputStream()
        JarOutputStream(BufferedOutputStream(byteArrayOutputStream)).use { jarOutputStream ->
            // put the original entries into the new jar
            // THIS DOES NOT MESS WITH THE ORDER OF THE FILES IN THE JAR!
            val metaEntries = jarFile.entries()
            while (metaEntries.hasMoreElements()) {
                entry = metaEntries.nextElement()

                // now add the entry to the jar (directories are never added to a jar)

                if (!entry.isDirectory) {
                    jarOutputStream.putNextEntry(entry)

                    jarFile.getInputStream(entry).use { inputStream ->
                        inputStream.copyTo(jarOutputStream)
                    }

                    jarOutputStream.flush()
                    jarOutputStream.closeEntry()
                }
            }

            // now add the files that we want to add.
            for ((inputFile, destPath) in filesToAdd) {
                entry = JarEntry(destPath)
                entry.time = inputFile.lastModified()

                // now add the entry to the jar
                FileInputStream(inputFile).use { inputStream ->
                    jarOutputStream.putNextEntry(entry)
                    inputStream.copyTo(jarOutputStream)
                }

                jarOutputStream.flush()
                jarOutputStream.closeEntry()
            }

            // finish the stream that we have been writing to
            jarOutputStream.finish()
        }


        // overwrite the jar file with the new one
        FileOutputStream(this.name, false).use { outputStream ->
            byteArrayOutputStream.writeTo(outputStream)
        }
    }
}
