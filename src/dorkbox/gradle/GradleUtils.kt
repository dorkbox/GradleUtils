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
@file:Suppress("unused")

package dorkbox.gradle

import dorkbox.gradle.deps.DependencyScanner
import dorkbox.gradle.deps.GetVersionInfoTask
import dorkbox.gradle.wrapper.GradleCheckTask
import dorkbox.gradle.wrapper.GradleUpdateTask
import org.gradle.api.*
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.java.archives.Manifest
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.*
import java.net.URI
import java.util.jar.*
import kotlin.reflect.KClass


/**
 * For managing (what should be common sense) gradle tasks, such as:
 *  - updating gradle
 *  - updating dependencies
 *  - checking version requirements
 */
class GradleUtils : Plugin<Project> {
    private lateinit var propertyMappingExtension: StaticMethodsAndTools

    companion object {
        // version
        private val buildText = """version""".toRegex()

        // VALID (because of different ways to assign values, we want to be explicit)
        // version = "1.0.0"
        // const val version = '1.0.0'
        // project.version = "1.0.0"
        private val buildFileVersionString = """^\s*\b(?:const val version|project\.version|version)\b\s*=\s*(['"])((?=.*\d)[\w.+-]*)(['"])$""".toRegex()
    }

    init {
        try {
            // Disable JarURLConnections caching. some JVMs don't have this
            URI("jar:file://dummy.jar!/").toURL().openConnection().defaultUseCaches = false
        } catch (_: IOException) {
        }
    }

    typealias FoundFunc = (String, String, File, Int) -> Unit

    fun Project.getVersionFromBuildFile(): String {
        // get version info by file parsing from gradle.build file
        buildFile.useLines { lines ->
            lines.forEach { line ->
                val trimmedLine = line.trim()

                if (line.contains(buildText)) {
                    val matchResult = buildFileVersionString.find(trimmedLine)
                    if (matchResult != null) {
                        try {
                            val (_, ver, _) = matchResult.destructured
                            return ver
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        return ""
    }

    override fun apply(project: Project) {
        StaticMethodsAndTools.apply(project, "java")
        StaticMethodsAndTools.apply(project, "java-library")
        StaticMethodsAndTools.apply(project, "idea")

        println("\t${project.name} ${project.getVersionFromBuildFile()}")
        println("\tGradle ${project.gradle.gradleVersion}, Java ${JavaVersion.current()}")

        propertyMappingExtension = project.extensions.create("GradleUtils", StaticMethodsAndTools::class.java, project)
        val manifest = project.extensions.create("manifest", dorkbox.gradle.Manifest::class.java, project)

        // do absolutely NOTHING if we are not the root project.
        // the gradle wrapper CAN ONLY be applied to the ROOT project (in a multi-project build), otherwise it will FAIL
        // when trying to apply to the sub-projects.

        if (project == project.rootProject) {
            project.tasks.register<GradleUpdateTask>("updateGradleWrapper")
            project.tasks.register<GradleCheckTask>("checkGradleVersion")
        }

        project.tasks.register<GetVersionInfoTask>("checkDependencies") {
            group = "gradle"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Fetch the latest version information for project dependencies"

            savedProject.set(project)
        }


        project.tasks.register<CheckKotlinTask>("checkKotlin") {
            group = "other"
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            description = "Debug output for checking if kotlin is currently installed in the project"
        }
    }
}

fun Project.prepLibraries(): PrepLibrariesTask {
    return this.tasks.maybeCreate("prepareLibraries", PrepLibrariesTask::class.java)
}

fun Project.getDependenciesAsClasspath(): String {
    return this.prepLibraries().getAsClasspath()
}

fun Project.getCompileLibraries(): Map<File, String> {
    return PrepLibrariesTask.collectCompileLibraries(this.rootProject.allprojects.toTypedArray())
}

fun Project.getRuntimeLibraries(): Map<File, String> {
    return PrepLibrariesTask.collectRuntimeLibraries(this.rootProject.allprojects.toTypedArray())
}

fun Project.copyLibrariesTo(location: File) {
    return this.project.prepLibraries().copyLibrariesTo(location)
}

fun Project.copyAllLibrariesTo(location: File) {
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


/**
 * Make sure that the creation of manifests happens AFTER task configuration, but before task execution.
 *
 * There are dependency issues if the manifest is configured during task configuration.
 *
 * These are NOT lambda's because of issues with gradle.
 */
fun Jar.safeManifest(action: Action<in Manifest>) {
    val task = this

    task.doFirst(Action { task ->
        task as Jar

        task.manifest(Action { t ->
            action.execute(t) })
    })
}

/**
 * Gets all the Maven-style Repository URLs for the specified project (or for the root project if not specified).
 *
 * @param onlyRemote true to ONLY get the remote repositories (ie: don't include mavenLocal)
 */
fun Project.buildScriptRepositoryUrls(onlyRemote: Boolean = true): List<String> {
    val repositories = mutableListOf<String>()
    val instance = this.buildscript.repositories.filterIsInstance<org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository>()

    @Suppress("DuplicatedCode")
    instance.forEach { repo ->
        val resolver = repo.createResolver()
        if (resolver is org.gradle.api.internal.artifacts.repositories.resolver.MavenResolver) {
            // println("searching ${resolver.name}")
            // println(resolver.root)
            // all maven patterns are the same!
            // https://plugins.gradle.org/m2/com/dorkbox/Utilities/maven-metadata.xml
            // https://repo1.maven.org/maven2/com/dorkbox/Utilities/maven-metadata.xml
            // https://repo.maven.apache.org/com/dorkbox/Utilities/maven-metadata.xml

            if ((onlyRemote && !resolver.isLocal) || !onlyRemote) {
                try {
                    val toURL = resolver.root.toASCIIString()
                    if (toURL.endsWith('/')) {
                        repositories.add(toURL)
                    } else {
                        // the root doesn't always end with a '/', and we must guarantee that
                        repositories.add("$toURL/")
                    }
                } catch (ignored: Exception) {
                }
            }
        }
    }
    return repositories
}

/**
 * Gets all the Maven-style Repository URLs for the specified project (or for the root project if not specified).
 *
 * @param onlyRemote true to ONLY get the remote repositories (ie: don't include mavenLocal)
 */
fun Project.getProjectRepositoryUrls(onlyRemote: Boolean = true): List<String> {
    val repositories = mutableListOf<String>()
    val instance = this.repositories.filterIsInstance<org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository>()

    @Suppress("DuplicatedCode")
    instance.forEach { repo ->
        val resolver = repo.createResolver()
        if (resolver is org.gradle.api.internal.artifacts.repositories.resolver.MavenResolver) {
            // println("searching ${resolver.name}")
            // println(resolver.root)
            // all maven patterns are the same!
            // https://plugins.gradle.org/m2/com/dorkbox/Utilities/maven-metadata.xml
            // https://repo1.maven.org/maven2/com/dorkbox/Utilities/maven-metadata.xml
            // https://repo.maven.apache.org/com/dorkbox/Utilities/maven-metadata.xml

            if ((onlyRemote && !resolver.isLocal) || !onlyRemote) {
                try {
                    val toURL = resolver.root.toASCIIString()
                    if (toURL.endsWith('/')) {
                        repositories.add(toURL)
                    } else {
                        // the root doesn't always end with a '/', and we must guarantee that
                        repositories.add("$toURL/")
                    }
                } catch (ignored: Exception) {
                }
            }
        }
    }
    return repositories
}


/**
 * Resolves all dependencies of the project buildscript
 *
 * THIS MUST BE IN "afterEvaluate" or run from a specific task.
 */
fun Project.resolveBuildScriptDependencies(): List<DependencyScanner.Maven> {
    return this.buildscript.configurations.flatMap { config ->
        config.resolvedConfiguration
            .lenientConfiguration
            .firstLevelModuleDependencies
            .mapNotNull { dep ->
                val module = dep.module.id
                val group = module.group
                val name = module.name
                val version = module.version

                DependencyScanner.Maven(group, name, version)
            }
    }
}


/**
 * Resolves all *declared* dependencies of the project
 *
 * THIS MUST BE IN "afterEvaluate" or run from a specific task.
 */
fun Project.resolveAllDeclaredDependencies(): List<DependencyScanner.DependencyData> {
    // NOTE: we cannot createTree("compile") and createTree("runtime") using the same existingNames and expect correct results.
    // This is because a dependency might exist for compile and runtime, but have different children, therefore, the list
    // will be incomplete

    // there will be DUPLICATES! (we don't care about children or hierarchy, so we remove the dupes)
    return (DependencyScanner.scan(this, "compile", false) +
            DependencyScanner.scan(this, "runtime", false) +
            DependencyScanner.scan(this, "test", false)
            ).toSet().toList()
}


/**
 * Recursively resolves all child dependencies of the project
 *
 * THIS MUST BE IN "afterEvaluate" or run from a specific task.
 */
fun Project.resolveAllDependencies(): List<DependencyScanner.DependencyData> {
    // NOTE: we cannot createTree("compile") and createTree("runtime") using the same exitingNames and expect correct results.
    // This is because a dependency might exist for compile and runtime, but have different children, therefore, the list
    // will be incomplete

    // there will be DUPLICATES! (we don't care about children or hierarchy, so we remove the dupes)
    return (DependencyScanner.scan(this, "compile") +
            DependencyScanner.scan(this, "runtime") +
            DependencyScanner.scan(this, "test")
            ).toSet().toList()
}

/**
 * Recursively resolves all child compile dependencies of the project
 *
 * THIS MUST BE IN "afterEvaluate" or run from a specific task.
 */
fun Project.resolveCompileDependencies(): DependencyScanner.ProjectDependencies {
    val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
    val existingNames = mutableMapOf<String, DependencyScanner.Dependency>()

    DependencyScanner.createTree(this, "compileClasspath", projectDependencies, existingNames)

    return DependencyScanner.ProjectDependencies(projectDependencies, existingNames.map { it.value })
}

/**
 * Recursively resolves all child runtime dependencies of the project
 *
 * THIS MUST BE IN "afterEvaluate" or run from a specific task.
 */
fun Project.resolveRuntimeDependencies(): DependencyScanner.ProjectDependencies {
    val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
    val existingNames = mutableMapOf<String, DependencyScanner.Dependency>()

    DependencyScanner.createTree(this, "runtimeClasspath", projectDependencies, existingNames)

    return DependencyScanner.ProjectDependencies(projectDependencies, existingNames.map { it.value })
}

/**
 * Recursively resolves all child test dependencies of the project
 *
 * THIS MUST BE IN "afterEvaluate" or run from a specific task.
 */
fun Project.resolveTestDependencies(): DependencyScanner.ProjectDependencies {
    val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
    val existingNames = mutableMapOf<String, DependencyScanner.Dependency>()

    DependencyScanner.createTree(this, "testClasspath", projectDependencies, existingNames)

    return DependencyScanner.ProjectDependencies(projectDependencies, existingNames.map { it.value })
}


/*
 * Copyright 2025  Danilo Pianini
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
 *
 * https://www.danilopianini.org
 * https://github.com/DanySK/publish-on-central
 */
inline fun <reified T : Any> Project.propertyWithDefault(default: T?): Property<T> =
    objects.property<T>().apply { convention(default) }

/*
 * Copyright 2025  Danilo Pianini
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
 *
 * https://www.danilopianini.org
 * https://github.com/DanySK/publish-on-central
 */
inline fun <reified T : Any> Project.propertyWithDefaultProvider(noinline default: () -> T?): Property<T> =
    objects.property<T>().apply { convention(provider(default)) }

/*
 * Copyright 2025  Danilo Pianini
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
 *
 * https://www.danilopianini.org
 * https://github.com/DanySK/publish-on-central
 */
fun <T : Task> Project.registerTaskIfNeeded(
    name: String,
    type: KClass<T>,
    vararg parameters: Any = emptyArray(),
    configuration: T.() -> Unit = { },
): TaskProvider<out Task> = runCatching { tasks.named(name) }
    .recover { exception ->
        when (exception) {
            is UnknownTaskException ->
                tasks.register(name, type, *parameters).apply { configure(configuration) }
            else -> throw exception
        }
    }.getOrThrow()

// https://www.danilopianini.org
//       https://github.com/DanySK/publish-on-central
//       Copyright 2025
//         Danilo Pianini
fun Project.registerTaskIfNeeded(
    name: String,
    vararg parameters: Any = emptyArray(),
    configuration: DefaultTask.() -> Unit = { },
): TaskProvider<out Task> = registerTaskIfNeeded(
    name = name,
    type = DefaultTask::class,
    parameters = parameters,
    configuration = configuration,
)
