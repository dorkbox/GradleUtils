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

package dorkbox.gradle.jpms

import dorkbox.gradle.StaticMethodsAndTools
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
class JpmsMultiRelease(javaVersion: JavaVersion, private val project: Project, gradleUtils: StaticMethodsAndTools) {
    val ver: String = javaVersion.majorVersion

    // If the kotlin plugin is applied, and there is a compileKotlin task. Then kotlin is enabled
    val hasKotlin = gradleUtils.hasKotlin

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

    val lower = "jpms"
    val upper = "Jpms"

    // plugin provided
    // These generate warnings in Gradle 8.x -- Fixed in 8.4
    val mainX: SourceSet = sourceSets.maybeCreate("${lower}Main")
    val testX: SourceSet = sourceSets.maybeCreate("${lower}Test")

    // the compile task NAME must match the source-set name
    val compileMainXJava: JavaCompile = project.tasks.named("compile${upper}MainJava", JavaCompile::class.java).get()
    val compileTestXJava: JavaCompile = project.tasks.named("compile${upper}TestJava", JavaCompile::class.java).get()

    val compileModuleInfoX: TaskProvider<JavaCompile> = project.tasks.register<JavaCompile>("compileJpmsModuleInfo")

    lateinit var compileMainXKotlin: TaskProvider<KotlinCompile>
    lateinit var compileTestXKotlin: TaskProvider<KotlinCompile>

    // have to create a task to get the files to get "picked up" by intellij/gradle. No *test* task? Then gradle/intellij won't be able run
    // the tests, even if you MANUALLY tell intellij to run a test from the sources dir
    // https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html
    val runTestX: TaskProvider<Test> = project.tasks.register<Test>("${lower}Test")

    init {
        if (moduleFile == null) {
            throw GradleException("Cannot manage JPMS build without a `module-info.java` file.")
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

        if (hasKotlin) {
            // can only setup the NORMAL tasks first
            compileMainKotlin = project.tasks.named("compileKotlin", KotlinCompile::class.java).get()
            compileTestKotlin = project.tasks.named("compileTestKotlin", KotlinCompile::class.java).get()
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


        // setup compile/runtime project dependencies
        mainX.apply {
            val files = project.files("src$ver")

            java { task ->
                // I don't like the opinionated sonatype directory structure.
                task.setSrcDirs(files)
                task.include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...
                task.exclude("**/module-info.java", "**/EmptyClass.java") // we have to compile these in a different step!

                // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
            }

            if (hasKotlin) {
                val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("${lower}Main")

                kotlin.kotlin.apply {
                    // I don't like the opinionated sonatype directory structure.
                    setSrcDirs(files)
                    include("**/*.kt") // want to include kotlin files for the source. 'setSrcDirs' resets includes...

                    exclude("**/module-info.java", "**/EmptyClass.kt") // we have to compile these in a different step!

                    // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
                }
            }

            val resourceFiles = project.files("resources$ver")
            resources.setSrcDirs(resourceFiles)

            // weird "+=" way of writing this because .addAll didn't work
            StaticMethodsAndTools.idea(project) {
                module.sourceDirs = module.sourceDirs + files.files
                module.resourceDirs = module.resourceDirs + resourceFiles.files
            }

            compileClasspath += main.compileClasspath
            runtimeClasspath += main.runtimeClasspath
        }

        testX.apply {
            val files = project.files("test$ver")

            java { task ->
                task.setSrcDirs(files)
                task.include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...

                // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
            }

            if (hasKotlin) {
                val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("${lower}Test")

                kotlin.kotlin.apply {
                    // I don't like the opinionated sonatype directory structure.
                    setSrcDirs(files)
                    include("**/*.kt") // want to include kotlin files for the source. 'setSrcDirs' resets includes...

                    exclude("**/module-info.java", "**/EmptyClass.kt") // we have to compile these in a different step!

                    // note: if we set the destination path, that location will be DELETED when the compile for these sources starts...
                }
            }

            val resourceFiles = project.files("testResources$ver")
            resources.setSrcDirs(resourceFiles)

            // weird "+=" way of writing this because .addAll didn't work
            StaticMethodsAndTools.idea(project) {
                module.testSources.from(files.files)
                module.testResources.from(resourceFiles.files)
            }

            compileClasspath += mainX.compileClasspath + test.compileClasspath
            runtimeClasspath += mainX.runtimeClasspath + test.runtimeClasspath
        }

        // run the testX verification
        runTestX.configure { task ->
//            dependsOn("test")
            task.description = "Runs Java $ver tests"
            task.group = "verification"

//            shouldRunAfter("test")

            // The directories for the compiled test sources.
            task.testClassesDirs = testX.output.classesDirs
            task.classpath += testX.runtimeClasspath
        }

        //////////////
        // done setting info for the source-sets, now we can setup configurations and other tasks
        // if this is done out-of-order, things aren't configured correctly
        //////////////

        if (hasKotlin) {
            compileMainXKotlin = project.tasks.named("compile${upper}MainKotlin", KotlinCompile::class.java)
            compileTestXKotlin = project.tasks.named("compile${upper}TestKotlin", KotlinCompile::class.java)
        }

        // have to setup the configurations, so dependencies work correctly
        val configs = project.configurations

        configs.maybeCreate("${lower}MainImplementation").extendsFrom(configs.getByName("implementation")).extendsFrom(configs.getByName("compileOnly"))
        configs.maybeCreate("${lower}MainRuntime").extendsFrom(configs.getByName("implementation")).extendsFrom(configs.getByName("runtimeOnly"))
        configs.maybeCreate("${lower}MainCompileOnly").extendsFrom(configs.getByName("compileOnly"))

        configs.maybeCreate("${upper}TestImplementation").extendsFrom(configs.getByName("testImplementation")).extendsFrom(configs.getByName("testCompileOnly"))
        configs.maybeCreate("${upper}TestRuntime").extendsFrom(configs.getByName("testImplementation")).extendsFrom(configs.getByName("testRuntimeOnly"))
        configs.maybeCreate("${upper}TestCompileOnly").extendsFrom(configs.getByName("testCompileOnly"))


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
            compileMainXKotlin.configure { task ->
                task.dependsOn(compileMainKotlin)

                task.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(ver))

                // must be the same module name as the regular one (which is the project name). If it is a different name, it crashes at runtime
                task.compilerOptions.moduleName.set(project.name)
            }

            compileTestXKotlin.configure { task ->
                task.dependsOn(compileTestKotlin)

                task.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(ver))

                // must be the same module name as the regular one (which is the project name). If it is a different name, it crashes at runtime
                task.compilerOptions.moduleName.set(project.name)
            }
        }

        compileModuleInfoX.configure { task ->
            // we need all the compiled classes before compiling module-info.java
            task.dependsOn(compileMainJava)
            if (hasKotlin) {
                task.dependsOn(compileMainKotlin)
            }

            val proj = this@JpmsMultiRelease.project

            val allSource = proj.files(
                main.allSource.srcDirs,
                mainX.allSource.srcDirs
            )

            task.source = allSource.asFileTree // the files live in this location
            task.include("**/module-info.java")

            task.sourceCompatibility = ver
            task.targetCompatibility = ver

            task.inputs.property("moduleName", moduleName)

            task.destinationDirectory.set(compileMainXJava.destinationDirectory.asFile.orNull)
            task.classpath = this@JpmsMultiRelease.project.files() // this resets the classpath. we use the module-path instead!


            // modules require this!
            task.doFirst {
                val allCompiled = if (hasKotlin) {
                    proj.files(compileMainJava.destinationDirectory.asFile.orNull, compileMainKotlin.destinationDirectory.asFile.orNull)
                } else {
                    proj.files(compileMainJava.destinationDirectory.asFile.orNull)
                }

                // the SOURCE of the module-info.java file. It uses **EVERYTHING**
                task.options.sourcepath = allSource
                task.options.compilerArgs.addAll(listOf(
                    "-implicit:none",
                    "-Xpkginfo:always",  // compile the package-info.java files as well (normally it does not)
                    "--module-path", main.compileClasspath.asPath,
                    "--patch-module", "$moduleName=" + allCompiled.asPath // add our existing, compiled classes so module-info can find them
                ))
            }


            task.doLast {
                val intellijClasses = File("${this@JpmsMultiRelease.project.layout.buildDirectory}/classes-intellij")
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
                }
            }
        }

        project.tasks.named<Jar>("jar", Jar::class.java).configure { task ->
            task.dependsOn(compileModuleInfoX)

            // NOTE: This syntax screws up, and the entire contents of the jar are in the wrong place...
            // from(mainX.output.classesDirs) {
            //     exclude("META-INF")
            //     into("META-INF/versions/$ver")
            // }
            task.from(mainX.output.classesDirs)


            val sourcePaths = mainX.output.classesDirs.map {it.absolutePath}.toSet()
//            println("SOURCE PATHS+ $sourcePaths")

            task.doFirst {
                // this is how to correctly RE-MAP the location of files in jar
                task.eachFile { details ->
                    val absolutePath = details.file.absolutePath
                    val length = details.path.length + 1

                    val sourceDir = absolutePath.substring(0, absolutePath.length - length)

                    // println("checking file: $absolutePath")
                    // println("checking file: $sourceDir")

                    if (sourcePaths.contains(sourceDir)) {
                        // println("Moving: " + absolutePath)
                        // println("      : " + details.path)
                        details.path = "META-INF/versions/${ver}/${details.path}"
                    }
                }
            }

            // this is required for making the java 9+ multi-release version possible
            task.manifest.attributes["Multi-Release"] = "true"
        }
    }
}
