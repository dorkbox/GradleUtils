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

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

open class
PrepLibrariesTask : DefaultTask() {

    companion object {
        private val lock = ReentrantReadWriteLock()
        private val allLibraries = mutableMapOf<String, MutableMap<File, String>>()
        private val allProjectLibraries = mutableMapOf<File, String>()

        fun projectLibs(project: Project): MutableMap<File, String> {
            lock.read {
                val key = project.name
                val map = allLibraries[key]

                if (map == null) {
                    val newMap = mutableMapOf<File, String>()
                    allLibraries[key] = newMap
                    return newMap
                }

                return map
            }
        }

        fun collectLibraries(projects: Array<out Project>): Map<File, String> {
            lock.read {
                if (allProjectLibraries.isNotEmpty()) {
                    return allProjectLibraries
                }
            }
            println("\tCollecting all libraries for: ${projects.joinToString(",") { it.name }}")

            lock.write {
                val librariesByFileName = mutableMapOf<String, File>()

                projects.forEach { project ->
                    val tools = StaticMethodsAndTools(project)
                    collectLibs(tools, project, allProjectLibraries, librariesByFileName)
                }

                return allProjectLibraries
            }
        }

        fun copyLibrariesTo(projects: Array<out Project>): Action<CopySpec> {
            @Suppress("ObjectLiteralToLambda")
            val action = object: Action<CopySpec> {
                override fun execute(copySpec: CopySpec) {
                    // if there is a clean, we DO NOT run!
                    // no need to check all projects, because this checks the parent gradle tasks
                    if (!shouldRun(projects.first())) {
                        return
                    }

                    val projLibraries = collectLibraries(projects)
                    println("\tCopying libraries for ${projects.joinToString(",") { it.name }}")

                    synchronized(projLibraries) {
                        projLibraries.forEach { (file, fileName) ->
                            copySpec.from(file) {
                                it.rename {
                                    fileName
                                }
                            }
                        }
                    }
                }
            }

            return action
        }

        private fun collectLibs(
            tools: StaticMethodsAndTools,
            project: Project,
            librariesByFile: MutableMap<File, String>,
            librariesByFileName: MutableMap<String, File>
        ) {
            val resolveAllDependencies = tools.resolveAllDependencies(project).flatMap { it.artifacts }

            resolveAllDependencies.forEach { artifact ->
                val file = artifact.file
                var fileName = file.name
                var firstNumCheck = 0

                while (librariesByFileName.containsKey(fileName)) {
                    // whoops! this is not good! Rename the file so it will be included. THIS PROBLEM ACTUALLY EXISTS, and is by accident!
                    // if the target FILE is the same file (as the filename) then it's OK for this to be a duplicate
                    if (file != librariesByFileName[fileName]) {
                        fileName = "${file.nameWithoutExtension}_DUP_${firstNumCheck++}.${file.extension}"
                    } else {
                        // the file name and path are the same, meaning this is just a duplicate library
                        // instead of a DIFFERENT library with the same library file name.
                        break
                    }
                }

                if (firstNumCheck != 0) {
                    println("\tTarget file exists already! Renaming to $fileName")
                }

                // println("adding: " + file)
                librariesByFileName[fileName] = file
                librariesByFile[file] = fileName
            }
        }

        fun shouldRun(project: Project): Boolean {
            val taskNames = project.gradle.startParameter.taskNames
            if (taskNames.isEmpty()) {
                return false
            }

            val cleanTasks = taskNames.filter { it.contains("clean") }
            //        println("tasks: $taskNames")
            //        println("cleanTasks: $cleanTasks")
            if (cleanTasks.size == taskNames.size) {
                return false
            }

            return true
        }
    }


    private val tools = StaticMethodsAndTools(project)

    init {
        group = "build"
        description = "Prepares and checks the libraries used by all projects."

        outputs.cacheIf { false }
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        collectLibraries()
    }

    fun collectLibraries(): Map<File, String> {
        val projectLibs = projectLibs(project)

        if (projectLibs.isNotEmpty()) {
            return projectLibs
        }

//        println("\tCollecting libraries for ${project.name}")

        val librariesByFileName = mutableMapOf<String, File>()

        lock.write {
            collectLibs(tools, project, projectLibs, librariesByFileName)
        }

        return projectLibs
    }

    // get all jars needed on the library classpath, for RUNTIME (this is usually placed in the jar manifest)
    // NOTE: This must be referenced via a TASK, otherwise it will not work.
    fun getAsClasspath(): String {
        if (!shouldRun(project)) {
            return ""
        }


        val projLibraries = collectLibraries()
        println("\tGetting libraries as classpath for ${project.name}")

        val libraries = mutableMapOf<String, File>()

        val resolveAllDependencies = tools.resolveRuntimeDependencies(project).dependencies
        // we must synchronize on it for thread safety
        lock.read {
            resolveAllDependencies.forEach { dep ->
                dep.artifacts.forEach { artifact ->
                    val file = artifact.file

                    // get the file info from the reverse lookup, because we might have mangled the filename!
                    val cacheFileName = projLibraries[file]
                    if (cacheFileName == null) {
                        println("\tUnable to find: $file")
                        println("\\tExisting: ${projLibraries.map { it.key }.joinToString()}")
                    } else {
                        libraries[cacheFileName] = file
                    }
                }
            }

            return libraries.keys.sorted().joinToString(prefix = "lib/", separator = " lib/", postfix = "\r\n")
        }
    }

    // NOTE: This must be referenced via a TASK, otherwise it will not work.
    fun copyLibrariesTo(): Action<CopySpec> {
        @Suppress("ObjectLiteralToLambda")
        val action = object: Action<CopySpec> {
            override fun execute(t: CopySpec) {
                copyLibrariesTo(t)
            }
        }

        return action
    }

    fun copyLibrariesTo(copySpec: CopySpec) {
        if (!shouldRun(project)) {
            return
        }

        val projLibraries = collectLibraries()
        println("\tCopying libraries for ${project.name}")

        projLibraries.forEach { (file, fileName) ->
            copySpec.from(file) {
                it.rename {
                    fileName
                }
            }
        }
    }

    fun copyLibrariesTo(location: File) {
        if (!shouldRun(project)) {
            return
        }

        val projLibraries = collectLibraries()
        println("\tCopying libraries for ${project.name}")

        projLibraries.forEach { (file, fileName) ->
            val newFile = location.resolve(fileName)
            file.copyTo(newFile, overwrite = true )
        }
    }
}
