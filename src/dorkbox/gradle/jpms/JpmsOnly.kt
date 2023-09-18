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

package dorkbox.gradle.jpms

import dorkbox.gradle.StaticMethodsAndTools
import org.gradle.api.*
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

// from: http://mail.openjdk.java.net/pipermail/jigsaw-dev/2017-February/011306.html
/// If you move the module-info.java to the top-level directory directory then I would expect this should work:
//
//javac --release 8 -d target/classes src/main/java/com/example/A.java src/main/java/com/example/Version.java
//javac -d target/classes src/main/java/module-info.java
//javac -d target/classes-java9 -cp target/classes src/main/java9/com/example/A.java
//jar --create --file mr.jar -C target/classes . --release 9 -C
//target/classes-java9 .

object JpmsOnly {

    // check to see if we have a module-info.java file.
    //      if we do, then we are "JPMS" enabled, and require some fiddling.
    // it is INCREDIBLY stupid this is as difficult as this is.
    // https://youtrack.jetbrains.com/issue/KT-55389/Gradle-plugin-should-expose-an-extension-method-to-automatically-enable-JPMS-for-Kotlin-compiled-output
    fun runIfNecessary(javaVersion: JavaVersion, project: Project, gradleUtils: StaticMethodsAndTools) {
        val ver = javaVersion.majorVersion.toIntOrNull()

        if (ver == null || ver < 9) {
            // obviously not going to happen!
            return
        }


        // If the kotlin plugin is applied, and there is a compileKotlin task.. Then kotlin is enabled
        val hasKotlin = gradleUtils.hasKotlin

        val moduleFile = project.projectDir.walkTopDown().find { it.name == "module-info.java" }
        var moduleName: String

        if (moduleFile == null) {
            // Cannot manage JPMS build without a `module-info.java` file....
            return
        }

        val srcName = moduleFile.parentFile.name
        if (srcName.startsWith("src") && srcName.last().isDigit()) {
            // if the module-file is in our srcX directory, we ignore these steps, since we would be building a multi-release jar
            return
        }

        // also the source dirs have been configured/setup.
        moduleName = moduleFile.readLines()[0].split(" ")[1].trimEnd('{')
        if (moduleName.isEmpty()) {
            throw GradleException("The module name must be specified in the module-info file! Verify file: $moduleFile")
        }

        val info = when {
            hasKotlin -> "Initializing JPMS $ver, Java/Kotlin [$moduleName]"
            else -> "Initializing JPMS $ver, Java [$moduleName]"
        }
        println("\t$info")




        val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer

        val main: SourceSet  = sourceSets.named("main", org.gradle.api.tasks.SourceSet::class.java).get()
        val compileMainJava: JavaCompile = project.tasks.named("compileJava", JavaCompile::class.java).get()

        lateinit var compileMainKotlin: KotlinCompile

        if (hasKotlin) {
            // can only setup the NORMAL tasks first
            compileMainKotlin = project.tasks.named("compileKotlin", KotlinCompile::class.java).get()
        }

        // make sure defaults are loaded
        StaticMethodsAndTools.idea(project) {
            if (module.sourceDirs == null) {
                module.sourceDirs = setOf<File>()
            }

            // make sure there is something there!
            module.testSources.from(setOf<File>())
            module.testResources.from(setOf<File>())
        }

        project.tasks.named("compileJava", JavaCompile::class.java) {
            // modules require this!
            it.doFirst(object: Action<Task> {
                override fun execute(task: Task) {
                    task as JavaCompile

                    val allCompiled = if (hasKotlin) {
                        project.files(compileMainJava.destinationDirectory.asFile.orNull, compileMainKotlin.destinationDirectory.asFile.orNull)
                    } else {
                        project.files(compileMainJava.destinationDirectory.asFile.orNull)
                    }

                    // the SOURCE of the module-info.java file. It uses **EVERYTHING**
                    task.options.compilerArgs.addAll(listOf(
                        "-implicit:none",
                        "-Xpkginfo:always",  // compile the package-info.java files as well (normally it does not)
                        "--module-path", main.compileClasspath.asPath,
                        "--patch-module", "$moduleName=" + allCompiled.asPath // add our existing, compiled classes so module-info can find them
                    ))
                }
            })

            it.doLast(object: Action<Task> {
                override fun execute(task: Task) {
                    task as JavaCompile

                    val intellijClasses = File("${project.layout.buildDirectory.locationOnly.get()}/classes-intellij")
                    if (intellijClasses.exists()) {
                        // copy everything to intellij also. FORTUNATELY, we know it's only going to be the `module-info` and `package-info` classes!
                        val directory = task.destinationDirectory.asFile.get()

                        val moduleInfo = directory.walkTopDown().filter { it.name == "module-info.class" }.toList()
                        val packageInfo = directory.walkTopDown().filter { it.name == "package-info.class" }.toList()

                        val name = when {
                            moduleInfo.isNotEmpty() && packageInfo.isNotEmpty() -> "module-info and package-info"
                            moduleInfo.isNotEmpty() && packageInfo.isEmpty() -> "module-info"
                            else -> "package-info"
                        }

                        println("\tCopying $name files into the intellij classes location...")

                        moduleInfo.forEach {
                            val newLocation = File(intellijClasses, it.relativeTo(directory).path)
                            it.copyTo(newLocation, overwrite = true)
                        }

                        packageInfo.forEach {
                            val newLocation = File(intellijClasses, it.relativeTo(directory).path)
                            it.copyTo(newLocation, overwrite = true)
                        }
                    }
                }
            })
        }
    }
}
