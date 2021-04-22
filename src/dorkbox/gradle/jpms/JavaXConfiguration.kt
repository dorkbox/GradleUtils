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

import dorkbox.gradle.kotlin
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.internal.HasConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
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

@Suppress("MemberVisibilityCanBePrivate")
class JavaXConfiguration(javaVersion: JavaVersion, private val project: Project) {
    val ver: String = javaVersion.majorVersion

    // this cannot be ONLY a number, there must be something else -- intellij will *not* pickup the name if it's only a number
    val nameX = "_$ver"

    // If kotlin files are present(meaning kotlin is used), we should setup the kotlin tasks
    val hasKotlin = project.projectDir.walkTopDown().find { it.extension == "kt" }?.exists() ?: false
    val hasJava = project.projectDir.walkTopDown().find {
            it.extension == "java" && !(it.name == "module-info.java" ||
                                        it.name == "package-info.java" ||
                                        it.name == "EmptyClass.java") }?.exists() ?: false

    val moduleFile = project.projectDir.walkTopDown().find { it.name == "module-info.java" }
    var moduleName: String

    val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer

    // standard
    val main: SourceSet  = sourceSets.named("main", org.gradle.api.tasks.SourceSet::class.java).get()
    val test: SourceSet  = sourceSets.named("test", org.gradle.api.tasks.SourceSet::class.java).get()

    val compileMainJava: JavaCompile = project.tasks.named("compileJava", JavaCompile::class.java).get()
    val compileTestJava: JavaCompile = project.tasks.named("compileTestJava", JavaCompile::class.java).get()
    lateinit var compileMainKotlin: KotlinCompile
    lateinit var compileTestKotlin: KotlinCompile



    // plugin provided
    val mainX: SourceSet = sourceSets.maybeCreate("main$nameX")
    val testX: SourceSet = sourceSets.maybeCreate("test$nameX")

    // the compile task NAME must match the source-set name
    val compileMainXJava: JavaCompile = project.tasks.named("compileMain${nameX}Java", JavaCompile::class.java).get()
    val compileTestXJava: JavaCompile = project.tasks.named("compileTest${nameX}Java", JavaCompile::class.java).get()

    val compileModuleInfoX: JavaCompile = project.tasks.create("compileModuleInfo$nameX", JavaCompile::class.java)

    lateinit var compileMainXKotlin: KotlinCompile
    lateinit var compileTestXKotlin: KotlinCompile

    // have to create a task in order to the files to get "picked up" by intellij/gradle. No *test* task? Then gradle/intellij won't be able run
    // the tests, even if you MANUALLY tell intellij to run a test from the sources dir
    // https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html
    val runTestX: Test = project.tasks.create("test${nameX}", Test::class.java)

    init {
        if (moduleFile == null) {
            throw GradleException("Cannot manage JPMS build without a `module-info` file.")
        }
        // also the source dirs have been configured/setup.
        moduleName = moduleFile.readLines()[0].split(" ")[1].trimEnd('{')
        if (moduleName.isEmpty()) {
            throw GradleException("The module name must be specified in the module-info file! Verify file: $moduleFile")
        }

        val info = when {
            hasJava && hasKotlin -> "Initializing [JPMS $ver] '$moduleName' -> Java/Kotlin"
            hasJava -> "Initializing [JPMS $ver] '$moduleName' -> Java"
            hasKotlin -> "Initializing [JPMS $ver] '$moduleName' -> Kotlin"
            else -> throw GradleException("Unable to initialize unknown JPMS type, no Java or Kotlin files found.")
        }
        println("\t$info")

        if (hasKotlin) {
            // can only setup the NORMAL tasks first
            compileMainKotlin = project.tasks.named("compileKotlin", KotlinCompile::class.java).get()
            compileTestKotlin = project.tasks.named("compileTestKotlin", KotlinCompile::class.java).get()
        }

       // setup compile/runtime project dependencies
        mainX.apply {
            java.apply {
                // I don't like the opinionated sonatype directory structure.
                setSrcDirs(project.files("src$ver"))
                include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...
                exclude("**/module-info.java", "**/EmptyClass.java") // we have to compile these in a different step!

                // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
            }

            if (hasKotlin) {
                kotlin {
                    setSrcDirs(project.files("src$ver"))
                    include("**/*.kt") // want to include java files for the source. 'setSrcDirs' resets includes...

                    // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
                }
            }

            resources.setSrcDirs(project.files("resources$ver"))

            compileClasspath += main.compileClasspath + main.output
            runtimeClasspath += main.runtimeClasspath + main.output + compileClasspath
        }
        testX.apply {
            java.apply {
                setSrcDirs(project.files("test$ver"))
                include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...

                // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
            }

            if (hasKotlin) {
                kotlin {
                    setSrcDirs(project.files("test$ver"))
                    include("**/*.kt") // want to include java files for the source. 'setSrcDirs' resets includes...

                    // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
                }
            }

            resources.setSrcDirs(project.files("testResources$ver"))

            compileClasspath += mainX.compileClasspath + test.compileClasspath + test.output
            runtimeClasspath += mainX.runtimeClasspath + test.runtimeClasspath + test.output
        }


        // run the testX verification
        runTestX.apply {
            dependsOn("test")
            description = "Runs Java $ver tests"
            group = "verification"

            outputs.upToDateWhen { false }
            shouldRunAfter("test")

            // The directories for the compiled test sources.
            testClassesDirs = testX.output.classesDirs
            classpath = testX.runtimeClasspath
        }

        //////////////
        // done setting info for the source-sets, now we can setup configurations and other tasks
        // if this is done out-of-order, things aren't configured correctly
        //////////////

        if (hasKotlin) {
            compileMainXKotlin = project.tasks.named("compileMain${nameX}Kotlin", KotlinCompile::class.java).get()
            compileTestXKotlin = project.tasks.named("compileTest${nameX}Kotlin", KotlinCompile::class.java).get()
        }

        // have to setup the configurations, so dependencies work correctly
        val configs = project.configurations
        configs.maybeCreate("main${nameX}Implementation").extendsFrom(configs.getByName("implementation")).extendsFrom(configs.getByName("compileOnly"))
        configs.maybeCreate("main${nameX}Runtime").extendsFrom(configs.getByName("implementation")).extendsFrom(configs.getByName("runtimeOnly"))

        configs.maybeCreate("test${nameX}Implementation").extendsFrom(configs.getByName("testImplementation")).extendsFrom(configs.getByName("testCompileOnly"))
        configs.maybeCreate("test${nameX}Runtime").extendsFrom(configs.getByName("testImplementation")).extendsFrom(configs.getByName("testRuntimeOnly"))


        // setup task graph and compile version
        compileMainXJava.apply {
            dependsOn(compileMainJava)
            sourceCompatibility = ver
            targetCompatibility = ver
        }
        compileTestXJava.apply {
            dependsOn(compileTestJava)
            sourceCompatibility = ver
            targetCompatibility = ver
        }


        if (hasKotlin) {
            compileMainXKotlin.apply {
                dependsOn(compileMainKotlin)
                sourceCompatibility = ver
                targetCompatibility = ver
                kotlinOptions.jvmTarget = ver
                kotlinOptions.moduleName = compileMainKotlin.kotlinOptions.moduleName  // must be the same module name
            }

            compileTestXKotlin.apply {
                dependsOn(compileTestKotlin)
                sourceCompatibility = ver
                targetCompatibility = ver
                kotlinOptions.jvmTarget = ver
                kotlinOptions.moduleName = compileTestKotlin.kotlinOptions.moduleName  // must be the same module name
            }
        }

        compileModuleInfoX.apply {
            // we need all the compiled classes before compiling module-info.java
            dependsOn(compileMainJava)
            if (hasKotlin) {
                dependsOn(compileMainKotlin)
            }

            val proj = this@JavaXConfiguration.project

            val allSource = proj.files(
                main.allSource.srcDirs,
                mainX.allSource.srcDirs
            )

            val allCompiled = if (hasKotlin) {
                proj.files(
                    compileMainJava.destinationDir,
                    compileMainKotlin.destinationDir
                )
            } else {
                proj.files(
                    compileMainJava.destinationDir
                )
            }


            source = allSource.asFileTree // the files live in this location
            include("**/module-info.java")


            sourceCompatibility = ver
            targetCompatibility = ver

            inputs.property("moduleName", moduleName)

            destinationDir = compileMainXJava.destinationDir
            classpath = this@JavaXConfiguration.project.files() // this resets the classpath. we use the module-path instead!


            // modules require this!
            doFirst {
                // the SOURCE of the module-info.java file. It uses **EVERYTHING**
                options.sourcepath = allSource
                options.compilerArgs.addAll(listOf(
                    "-implicit:none",
                    "-Xpkginfo:always",  // compile the package-info.java files as well (normally it does not)
                    "--module-path", main.compileClasspath.asPath,
                    "--patch-module", "$moduleName=" + allCompiled.asPath // add our existing, compiled classes so module-info can find them
                ))
            }

            doLast {
                val intellijClasses = File("${this@JavaXConfiguration.project.buildDir}/classes-intellij")
                if (intellijClasses.exists()) {
                    // copy everything to intellij also. FORTUNATELY, we know it's only going to be the `module-info` and `package-info` classes!
                    val moduleInfo = destinationDir.walkTopDown().filter { it.name == "module-info.class" }.toList()
                    val packageInfo = destinationDir.walkTopDown().filter { it.name == "package-info.class" }.toList()

                    val name = when {
                        moduleInfo.isNotEmpty() && packageInfo.isNotEmpty() -> "module-info and package-info"
                        moduleInfo.isNotEmpty() && packageInfo.isEmpty() -> "module-info"
                        else -> "package-info"
                    }

                    println("\tCopying $name files into the intellij classes location...")

                    moduleInfo.forEach {
                        val newLocation = File(intellijClasses, it.relativeTo(destinationDir).path)
                        it.copyTo(newLocation, overwrite = true)
                    }
                }
            }
        }

        project.tasks.named("jar", Jar::class.java).get().apply {
            dependsOn(compileModuleInfoX)

            // NOTE: This syntax screws up, and the entire contents of the jar are in the wrong place...
            // from(mainX.output.classesDirs) {
            //     exclude("META-INF")
            //     into("META-INF/versions/$ver")
            // }
            from(mainX.output.classesDirs)


            val sourcePaths = mainX.output.classesDirs.map {it.absolutePath}.toSet()
//            println("SOURCE PATHS+ $sourcePaths")

            doFirst {
                // this is how to correctly RE-MAP the location of files in jar
                eachFile { details ->
                    val absolutePath = details.file.absolutePath
                    val length = details.path.length + 1

                    val sourceDir = absolutePath.substring(0, absolutePath.length - length)
                    if (sourcePaths.contains(sourceDir)) {
//                        println("Moving: " + absolutePath)
//                        println("      : " + details.path)
                        details.path = "META-INF/versions/${ver}/${details.path}"
                    }
                }
            }

            // this is required for making the java 9+ multi-release version possible
            manifest.attributes["Multi-Release"] = "true"
        }
    }
}
