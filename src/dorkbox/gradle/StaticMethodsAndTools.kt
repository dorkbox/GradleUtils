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

import dorkbox.gradle.deps.DependencyScanner
import dorkbox.gradle.jpms.JpmsMultiRelease
import dorkbox.gradle.jpms.JpmsOnly
import dorkbox.gradle.jpms.JpmsSourceSetContainer
import dorkbox.os.OS
import org.gradle.api.*
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.ide.idea.model.IdeaLanguageLevel
import org.gradle.util.GradleVersion
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties


@Suppress("unused", "MemberVisibilityCanBePrivate", "ObjectLiteralToLambda")
open class StaticMethodsAndTools(private val project: Project) {
    companion object {
        /**
         * If the kotlin plugin is applied, and there is a compileKotlin task.. Then kotlin is enabled
         * NOTE: This can ONLY be called from a task, it cannot be called globally!
         *
         * additionally, we want to do the `hasKotlin` check AFTER we have assigned kotlin source dirs!
         */
        fun hasKotlin(project: Project, debug: Boolean = false): Boolean {
            try {
                // check if plugin is available
                project.plugins.findPlugin("org.jetbrains.kotlin.jvm") ?: return false

                if (debug) println("\t${project.name} kotlin check:")
                if (debug) println("\t\tHas kotlin plugin")

                // this will check if the task exists, and throw an exception if it does not or return false
                project.tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).orNull ?: return false

                if (debug) println("\t\tHas compile kotlin task")

                project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").kotlin
                if (debug) println("\t\tHas kotlin source-set")

                return true

            } catch (e: Exception) {
                if (debug) e.printStackTrace()
            }

            return false
        }

        internal fun idea(project: Project, configure: org.gradle.plugins.ide.idea.model.IdeaModel.() -> Unit): Unit =
            project.extensions.configure("idea", configure)

        // required to make sure the plugins are correctly applied. ONLY applying it to the project WILL NOT work.
        // The plugin must also be applied to the root project
        // https://discuss.gradle.org/t/can-a-plugin-itself-add-buildscript-dependencies-and-then-apply-a-plugin/25039/4
        internal fun apply(project: Project, id: String) {
            if (project.rootProject.pluginManager.findPlugin(id) == null) {
                project.rootProject.pluginManager.apply(id)
            }

            if (project.pluginManager.findPlugin(id) == null) {
                project.pluginManager.apply(id)
            }
        }
    }

    val isUnix = org.gradle.internal.os.OperatingSystem.current().isUnix
    val isLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
    val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows

    @Volatile
    private var debug = false

    private var fixedSWT = false

    // this is lazy, because it MUST be initialized from a task!
    val hasKotlin: Boolean by lazy { hasKotlin(project, debug) }

    /**
     * Get the time now as a string. This is to reduce the import requirements in a gradle build file
     */
    fun now() = Instant.now().toString()

    /**
     * Shows info if kotlin is enabled, shows exact information as to what the source-set directories are for java and kotlin
     */
    fun debug() {
        debug = true

        project.afterEvaluate {
            val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer
            val main = sourceSets.getByName("main").java
            val test = sourceSets.getByName("test").java

            println("\tSource directories:")

            println("\t\tJava:")
            println("\t\t\tmain: ${main.srcDirs}")
            println("\t\t\ttest: ${test.srcDirs}")

            try {
                val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets
                val kMain = kotlin.getByName("main").kotlin
                val kTest = kotlin.getByName("test").kotlin

                println("\t\tKotlin:")
                println("\t\t\tmain: ${kMain.srcDirs}")
                println("\t\t\ttest: ${kTest.srcDirs}")
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Maps the property (key/value) pairs of a property file onto the specified target object. Also maps fields in the targetObject to the
     * project, if they have the same name relationship (ie: field name is "version", project method is "setVersion")
     */
    fun load(propertyFile: String, targetObject: Any) {
        val kClass = targetObject::class

        val propsFile = File(propertyFile).normalize()
        if (propsFile.canRead()) {
            println("\tLoading custom property data from: [$propsFile]")

            val props = Properties()
            propsFile.inputStream().use {
                props.load(it)
            }

            val extraProperties = kClass.declaredMemberProperties.filterIsInstance<KMutableProperty<String>>()
            val assignedExtraProperties = kClass.declaredMemberProperties.filterIsInstance<KProperty<String>>()

            // project functions that can be called for setting properties
            val propertyFunctions = Project::class.declaredMemberFunctions.filter { it.parameters.size == 2 }

            // THREE possibilities for property registration or assignment
            // 1) we have MANUALLY defined this property (via the configuration object)
            // 1) gradleUtil properties loaded first
            //      -> gradleUtil's adds a function that everyone else (plugin/task) can call to get values from properties
            // 2) gradleUtil properties loaded last
            //      -> others add a function that gradleUtil's call to set values from properties
            // get the module loaded registration functions (if they exist)
            val loaderFunctions: ArrayList<Plugin<Pair<String, String>>>?
            if (project.extensions.extraProperties.has("property_loader_functions")) {
                @Suppress("UNCHECKED_CAST")
                loaderFunctions = project.extensions.extraProperties["property_loader_functions"] as ArrayList<Plugin<Pair<String, String>>>?
            } else {
                loaderFunctions = null
            }

            props.forEach { (k, v) -> run {
                val key = k as String
                val value = v as String

                val member = extraProperties.find { it.name == key }
                // if we have a property name in our object, we set it.
                member?.setter?.call(kClass.objectInstance, value)

                // if we have a property name in our PROJECT, we set it
                // project functions that can be called for setting properties
                val setterName = "set${key.replaceFirstChar { it.titlecaseChar() }}"
                propertyFunctions.find { prop -> prop.name == setterName }?.call(project, value)

                // assign this as an "extra property"
                project.extensions.extraProperties.set(k, v)

                // apply this property to whatever loader functions have been dynamically applied
                val pair = Pair(key, value)
                loaderFunctions?.forEach {
                    it.apply(pair)
                }
            }}

            // assign target fields to our project (if our project has matching setters)
            assignedExtraProperties.forEach { prop ->
                val propertyName = prop.name
                val setterName = "set${propertyName.replaceFirstChar { it.titlecaseChar() }}"

                val projectMethod = propertyFunctions.find { it.name == setterName }
                if (projectMethod != null) {
                    val getter = prop.getter
                    if (getter.property.isConst) {
                        projectMethod.call(project, getter.call())
                    } else {
                        projectMethod.call(project, getter.call(kClass.objectInstance))
                    }
                }
            }
        }
    }

    /**
     * Validates the minimum version of gradle supported
     */
    fun minVersion(version: String) {
        val compared = GradleVersion.current().compareTo(GradleVersion.version(version))
        if (compared == -1) {
            throw GradleException("This project requires Gradle $version or higher.")
        }
    }

    /**
     * Validates the maximum version of gradle supported
     */
    fun maxVersion(version: String) {
        val compared = GradleVersion.current().compareTo(GradleVersion.version(version))
        if (compared == 1) {
            throw GradleException("This project requires Gradle $version or lower.")
        }
    }

    /**
     * Gets all of the Maven-style Repository URLs for the specified project (or for the root project if not specified).
     *
     * @param project which project to get the repository root URLs for
     * @param onlyRemote true to ONLY get the remote repositories (ie: don't include mavenLocal)
     */
    fun getProjectRepositoryUrls(project: Project = this.project, onlyRemote: Boolean = true): List<String> {
        val repositories = mutableListOf<String>()
        val instance = project.repositories.filterIsInstance<org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository>()

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
     * Gets all of the Maven-style Repository URLs for the specified project (or for the root project if not specified).
     *
     * @param project which project to get the repository root URLs for
     * @param onlyRemote true to ONLY get the remote repositories (ie: don't include mavenLocal)
     */
    fun getProjectBuildScriptRepositoryUrls(project: Project = this.project, onlyRemote: Boolean = true): List<String> {
        val repositories = mutableListOf<String>()
        val instance = project.buildscript.repositories.filterIsInstance<org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository>()

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
    fun resolveBuildScriptDependencies(project: Project = this.project): List<DependencyScanner.Maven> {
        return project.buildscript.configurations.flatMap { config ->
            config.resolvedConfiguration
                .lenientConfiguration
                .getFirstLevelModuleDependencies(Specs.SATISFIES_ALL)
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
    fun resolveAllDeclaredDependencies(project: Project = this.project): List<DependencyScanner.DependencyData> {
        // NOTE: we cannot createTree("compile") and createTree("runtime") using the same exitingNames and expect correct results.
        // This is because a dependency might exist for compile and runtime, but have different children, therefore, the list
        // will be incomplete

        // there will be DUPLICATES! (we don't care about children or hierarchy, so we remove the dupes)
        return (DependencyScanner.scan(project, "compileClasspath", false) +
                DependencyScanner.scan(project, "runtimeClasspath", false)
                ).toSet().toList()
    }


    /**
     * Recursively resolves all child dependencies of the project
     *
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun resolveAllDependencies(project: Project = this.project): List<DependencyScanner.DependencyData> {
        // NOTE: we cannot createTree("compile") and createTree("runtime") using the same exitingNames and expect correct results.
        // This is because a dependency might exist for compile and runtime, but have different children, therefore, the list
        // will be incomplete

        // there will be DUPLICATES! (we don't care about children or hierarchy, so we remove the dupes)
        return (DependencyScanner.scan(project, "compileClasspath") +
                DependencyScanner.scan(project, "runtimeClasspath")
                ).toSet().toList()
    }

    /**
     * Recursively resolves all child compile dependencies of the project
     *
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun resolveCompileDependencies(project: Project = this.project): DependencyScanner.ProjectDependencies {
        val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
        val existingNames = mutableMapOf<String, DependencyScanner.Dependency>()

        DependencyScanner.createTree(project, "compileClasspath", projectDependencies, existingNames)

        return DependencyScanner.ProjectDependencies(projectDependencies, existingNames.map { it.value })
    }

    /**
     * Recursively resolves all child compile dependencies of the project
     *
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun resolveRuntimeDependencies(project: Project = this.project): DependencyScanner.ProjectDependencies {
        val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
        val existingNames = mutableMapOf<String, DependencyScanner.Dependency>()

        DependencyScanner.createTree(project, "runtimeClasspath", projectDependencies, existingNames)

        return DependencyScanner.ProjectDependencies(projectDependencies, existingNames.map { it.value })
    }

    /**
     * set gradle project defaults, as used by dorkbox, llc
     */
    fun defaults() {
        addMavenRepositories()
        fixMavenPaths()
        defaultResolutionStrategy()
        defaultCompileOptions()
        fixIntellijPaths()
    }

    /**
     * Adds maven-local + maven-central repositories
     */
    fun addMavenRepositories() {
        project.repositories.apply {
            mavenLocal() // this must be first!
            mavenCentral()
        }
    }

    /**
     * Change the source input from the opinionated sonatype paths to a simpler directory
     */
    fun fixMavenPaths() {
        // it is SUPER annoying to use the opinionated sonatype directory structure. I don't like it. We pass in the "configuration" action
        // instead of doing it a different way, so the creation AND configuration of these sorucesets can occur. (Otherwise they won't)

        val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer
        val main = sourceSets.named("main", org.gradle.api.tasks.SourceSet::class.java).get()
        val test = sourceSets.named("test", org.gradle.api.tasks.SourceSet::class.java).get()

        main.apply {
            java.apply {
                setSrcDirs(project.files("src"))
                include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...
            }

            try {
                val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").kotlin
                kotlin.apply {
                    setSrcDirs(project.files("src"))
                    include("**/*.kt") // want to include kotlin files for the source. 'setSrcDirs' resets includes...
                }
            } catch (ignored: Exception) {
            }

            resources.setSrcDirs(project.files("resources"))
        }

        test.apply {
            java.apply {
                setSrcDirs(project.files("test"))
                include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...
            }

            try {
                val kotlin = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java).sourceSets.getByName("test").kotlin
                kotlin.apply {
                    setSrcDirs(project.files("test"))
                    include("**/*.kt") // want to include kotlin files for the source. 'setSrcDirs' resets includes...
                }
            } catch (ignored: Exception) {
            }

            resources.setSrcDirs(project.files("testResources"))
        }
    }

    /**
     * Fix the compiled output from intellij to be SEPARATE from gradle.
     *
     * NOTE: This only affects NEW projects inported into intellji from gradle!
     */
    fun fixIntellijPaths(location: String = "${project.buildDir}/classes-intellij") {
        val intellijDir = File(location)

        try {
            // put idea in its place! Not having this causes SO MANY PROBLEMS when building modules
            // println("Setting intellij Compile location to: $location")
            idea(project) {
                // https://youtrack.jetbrains.com/issue/IDEA-175172
                module {
                    // force the module to use OUR output dirs.
                    it.inheritOutputDirs = false

                    it.outputDir = intellijDir
                    it.testOutputDir = intellijDir

                    // by default, we ALWAYS want sources. If you have sources, you don't need javadoc (since the sources have them in it already)
                    it.isDownloadJavadoc = false
                    it.isDownloadSources = true
                }
            }

            // this has the side-effect of NOT creating the gradle directories....
            // also... if we CLEAN the project, the STANDARD build dir is DELETED. This messes up mixed kotlin + java projects, because the
            // INTELLIJ compiler will compile the GRADLE classes (not the intellij classes) to the WRONG location!
            //   This appears to be triggered by a file watcher on the build dir.
            //   A subsequent problem created by this, is when we go to compile an archive (jar/etc) NORMALLY (via gradle)... there will be duplicates!
            val hasClean = project.gradle.startParameter.taskNames.filter { taskName ->
                taskName.lowercase(Locale.getDefault()).contains("clean")
            }

            if (hasClean.isNotEmpty()) {
                val task = project.tasks.last { task -> task.name == hasClean.last() }

                task.doLast {
                    intellijDir.deleteRecursively()
                    createBuildDirs(project, intellijDir)
                }
            } else {
                // make sure that the source set directories all exist. THIS SHOULD NOT BE A PROBLEM! (but it is)
                project.afterEvaluate { prj ->
                    createBuildDirs(prj, intellijDir)
                }
            }
        } catch (ignored: Exception) {
            // likely that intellij is not used, so ignore errors from it
        }
    }

    private fun createBuildDirs(prj: Project, intellijDir: File) {
        val sourceSets = prj.extensions.getByName("sourceSets") as SourceSetContainer
        sourceSets.forEach { set ->
            set.output.classesDirs.forEach { dir ->
                // println("Dir: ${dir.absolutePath}")

                // ../classes/java/main -> java/main
                val classDir = dir.toRelativeString(intellijDir).substring(3).let { it.substring(it.indexOf('/') + 1) }
                val classDirRelative = File(intellijDir, classDir)

                if (!classDirRelative.exists()) {
                    classDirRelative.mkdirs()
                }

                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }
        }
    }


    /**
     * Configure a default resolution strategy. While not necessary, this is used for enforcing sane project builds
     */
    fun defaultResolutionStrategy() {
        project.configurations.forEach { config ->
            config.resolutionStrategy {
                it.apply {
                    // fail eagerly on version conflict (includes transitive dependencies)
                    // e.g. multiple different versions of the same dependency (group and name are equal)
                    failOnVersionConflict()

                    // if there is a version we specified, USE THAT VERSION (over transitive versions)
                    preferProjectModules()

                    // cache dynamic versions for 10 minutes
                    cacheDynamicVersionsFor(10 * 60, "seconds")

                    // don't cache changing modules at all
                    cacheChangingModulesFor(0, "seconds")
                }
            }
        }
    }

    /**
     * Always compile java with UTF-8, make it incremental, add -Xlint:unchecked, add -Xlint:deprecation, and compile `package-info.java` classes
     */
    fun defaultCompileOptions() {
        project.tasks.withType(JavaCompile::class.java, object: Action<JavaCompile> {
            override fun execute(task: JavaCompile) {
                task.options.encoding = "UTF-8"
                task.options.isIncremental = true

                // -Xlint:deprecation
                task.options.isDeprecation = true

                // -Xlint:unchecked
                task.options.compilerArgs.add("-Xlint:unchecked")
                task.options.compilerArgs.add("-Xpkginfo:always")
            }
        })
    }

    /**
     * Basic, default compile configurations
     */
    private fun compileConfiguration(javaVersion: JavaVersion) {
        val javaVer = javaVersion.toString()

        // NOTE: these must be anonymous inner classes because gradle cannot handle this in kotlin 1.5
        project.tasks.withType(JavaCompile::class.java, object: Action<Task> {
            override fun execute(task: Task) {
                task as JavaCompile
                task.doFirst(object: Action<Task> {
                    override fun execute(it: Task) {
                        it as JavaCompile
                        println("\tCompiling classes to Java ${JavaVersion.toVersion(it.targetCompatibility)}")
                    }
                })

                task.sourceCompatibility = javaVer
                task.targetCompatibility = javaVer
            }
        })

        // NOTE: these must be anonymous inner classes because gradle cannot handle this in kotlin 1.5
        project.tasks.withType(org.gradle.jvm.tasks.Jar::class.java, object: Action<Jar> {
            override fun execute(task: Jar) {
                task.duplicatesStrategy = DuplicatesStrategy.FAIL

                task.doLast(object: Action<Task> {
                    override fun execute(task: Task) {
                        task as Jar

                        if (task.didWork) {
                            val file = task.archiveFile.get().asFile
                            println("\t${file.path}\n\tSize: ${file.length().toDouble() / (1_000 * 1_000)} MB")
                        }
                    }
                })
            }
        })

        try {
            // also have to tell intellij (if present) to behave.
            idea(project) {
                module {
                    it.jdkName = javaVer
                    it.targetBytecodeVersion = javaVersion
                    it.languageLevel = IdeaLanguageLevel(javaVersion)

                    // by default, we ALWAYS want sources. If you have sources, you don't need javadoc (since the sources have them in it already)
                    it.isDownloadJavadoc = false
                    it.isDownloadSources = true
                }
            }
        } catch (ignored: Exception) {
        }
    }


    /**
     * Basic, default compile configurations
     */
    fun compileConfiguration(javaVersion: JavaVersion,
                             kotlinJavaVersion: JavaVersion = javaVersion,
                             kotlinActions: org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions.() -> Unit = {}) {
        val kotlinJavaVer = kotlinJavaVersion.toString().also {
            if (it.startsWith("1.")) {
                if (it == "1.6" || it == "1.8") {
                    it
                } else {
                    it.substring(2)
                }
            } else {
                it
            }
        }

        val kotlinVer = KotlinUtil.getKotlinVersion(project)

        compileConfiguration(javaVersion)

        try {
            // NOTE: these must be anonymous inner classes because gradle cannot handle this in kotlin 1.5
            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java, object: Action<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                override fun execute(task: org.jetbrains.kotlin.gradle.tasks.KotlinCompile) {
                    task.doFirst(object: Action<Task> {
                        override fun execute(it: Task) {
                            it as org.jetbrains.kotlin.gradle.tasks.KotlinCompile
                            println("\tCompiling classes to Kotlin ${it.kotlinOptions.languageVersion}, Java ${it.kotlinOptions.jvmTarget}")
                        }
                    })


                    task.kotlinOptions.jvmTarget = kotlinJavaVer

                    // default is whatever the version is that we are running, or XXXXX if we cannot figure it out
                    task.kotlinOptions.apiVersion = kotlinVer
                    task.kotlinOptions.languageVersion = kotlinVer

                    // see: https://kotlinlang.org/docs/reference/using-gradle.html
                    kotlinActions(task.kotlinOptions)
                }
            })

            // now we auto-check if it's necessary to enable JPMS support for PRIMARY locations (ie, not the src9 location)
            JpmsOnly.runIfNecessary(javaVersion, project, this)
        } catch (ignored: Exception) {
        }
    }

    /**
     * Get the SWT maven ID based on the os/arch. ALSO fix SWT maven configuration IDs
     *
     * This is spectacularly frustrating because there aren't "normal" releases of SWT.
     */
    fun getSwtMavenId(version: String): String {
        // SEE: https://repo1.maven.org/maven2/org/eclipse/platform/

        // windows
        // org.eclipse.swt.win32.win32.x86
        // org.eclipse.swt.win32.win32.x86_64

        // linux
        // org.eclipse.swt.gtk.linux.x86
        // org.eclipse.swt.gtk.linux.x86_64

        // macos
        // org.eclipse.swt.cocoa.macosx.x86_64


        val swtType = when {
            OS.isMacOsX -> {
                when {
                    OS.is32bit -> SwtType.UNKNOWN
                    OS.is64bit -> SwtType.MAC_64
                    OS.isArm -> SwtType.MAC_AARCH64
                    else -> SwtType.UNKNOWN
                }
            }
            OS.isWindows -> {
                when {
                    OS.is32bit -> SwtType.WIN_32
                    OS.is64bit -> SwtType.WIN_64
                    else -> SwtType.UNKNOWN
                }
            }

            OS.isLinux -> {
                when {
                    OS.is32bit -> SwtType.LINUX_32
                    OS.is64bit -> SwtType.LINUX_64
                    OS.isArm -> SwtType.LINUX_AARCH64
                    else -> SwtType.UNKNOWN
                }
            }
            else -> SwtType.UNKNOWN
        }

        val fullId = swtType.fullId(version)

        if (!fixedSWT) {
            fixedSWT = true

            project.configurations.all { config ->
                config.resolutionStrategy { strategy ->
                    strategy.dependencySubstitution { sub ->
                        // The maven property ${osgi.platform} is not handled by Gradle for the SWT builds
                        // so we replace the dependency, using the osgi platform from the project settings
                        sub.substitute(sub.module("org.eclipse.platform:org.eclipse.swt.\${osgi.platform}"))
                            .using(sub.module(fullId))
                    }
                }
            }
        }

        return fullId
    }

    /**
     * Load JPMS for a specific java version using the default configuration
     */
    fun jpms(javaVersion: JavaVersion): JpmsMultiRelease {
        return JpmsMultiRelease(javaVersion, project, this)
    }

    /**
     * Load and configure JPMS for a specific java version
     */
    fun jpms(javaVersion: JavaVersion, block: JpmsSourceSetContainer.() -> Unit): JpmsMultiRelease {
        val javaX = JpmsMultiRelease(javaVersion, project, this)
        block(JpmsSourceSetContainer(javaX))
        return javaX
    }

    /**
     * Fix issues where code (usually test code) needs access to **INTERNAL** scope objects.
     *  -- at the moment, this only fixes gradle -- not intellij
     *     There are also gradle 8 warnings when using this.
     *
     *  https://stackoverflow.com/questions/59072889/how-to-test-kotlin-function-declared-internal-from-within-tests-when-java-test
     *  https://youtrack.jetbrains.com/issue/KT-20760
     *  https://youtrack.jetbrains.com/issue/KT-45787
     *  https://stackoverflow.com/questions/57050889/kotlin-internal-members-not-accessible-from-alternative-test-source-set-in-gradl
     *  https://youtrack.jetbrains.com/issue/KT-34901
     *
     *  (related)
     *  https://github.com/JetBrains/kotlin/blob/master/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/K2JVMCompilerArguments.kt
     *  https://github.com/bazelbuild/rules_kotlin/pull/465
     *  https://github.com/bazelbuild/rules_kotlin/issues/211
     *
     * Two things are required for this to work
     *
     * 1) The kotlin module names must be the same
     * 2) The kotlin modules must be associated
     */
    fun allowKotlinInternalAccessForTests(moduleName: String, vararg accessGroup: AccessGroup) {
        try {
            // Make sure to cleanup any possible license file on clean
            println("\tAllowing kotlin internal access for $moduleName")

            // NOTE: these must be anonymous inner classes because gradle cannot handle this in kotlin 1.5
            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java, object: Action<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
                override fun execute(task: org.jetbrains.kotlin.gradle.tasks.KotlinCompile) {
                    // must be the same module name as the regular one (which is the project name). If it is a different name, it crashes at runtime
                    task.kotlinOptions.moduleName = moduleName
                }
            })

            accessGroup.forEach {
                // allow code in a *different* directory access to "internal" scope members of code.
                // THIS FIXES GRADLE - BUT NOT INTELLIJ!
                val kotlinExt = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
                kotlinExt.target.compilations.getByName(it.sourceName).apply {
                    it.targetNames.forEach { targetName ->
                        associateWith(target.compilations.getByName(targetName))
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    class AccessGroup(val sourceName: String, vararg val targetNames: String)


    // https://docs.oracle.com/javase/7/docs/api/java/security/MessageDigest.html
    //  Every implementation of the Java platform is required to support the following standard MessageDigest algorithms:
    //    MD5
    //    SHA-1
    //    SHA-256

    fun md5(file: File?): ByteArray {
        if (file == null || !file.canRead()) {
            return ByteArray(0)
        }

        val md = MessageDigest.getInstance("MD5")
        file.forEachBlock { bytes: ByteArray, bytesRead: Int ->
            md.update(bytes, 0, bytesRead)
        }
        return md.digest()
    }

    fun md5(text: String?): ByteArray {
        if (text.isNullOrEmpty()) {
            return ByteArray(0)
        }

        val md = MessageDigest.getInstance("MD5")
        val bytes = text.toByteArray(Charsets.UTF_8)
        md.update(bytes, 0, bytes.size)

        return md.digest()
    }

    fun sha1(file: File?): ByteArray {
        if (file == null || !file.canRead()) {
            return ByteArray(0)
        }

        val md = MessageDigest.getInstance("SHA-1")
        file.forEachBlock { bytes: ByteArray, bytesRead: Int ->
            md.update(bytes, 0, bytesRead)
        }
        return md.digest()
    }

    fun sha1(text: String?): ByteArray {
        if (text.isNullOrEmpty()) {
            return ByteArray(0)
        }

        val md = MessageDigest.getInstance("SHA-1")
        val bytes = text.toByteArray(Charsets.UTF_8)
        md.update(bytes, 0, bytes.size)

        return md.digest()
    }

    fun sha256(file: File?): ByteArray {
        if (file == null || !file.canRead()) {
            return ByteArray(0)
        }

        val md = MessageDigest.getInstance("SHA-256")
        file.forEachBlock { bytes: ByteArray, bytesRead: Int ->
            md.update(bytes, 0, bytesRead)
        }
        return md.digest()
    }

    fun sha256(text: String?): ByteArray {
        if (text.isNullOrEmpty()) {
            return ByteArray(0)
        }

        val md = MessageDigest.getInstance("SHA-256")
        val bytes = text.toByteArray(Charsets.UTF_8)
        md.update(bytes, 0, bytes.size)

        return md.digest()
    }
}
