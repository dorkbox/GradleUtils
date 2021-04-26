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
import dorkbox.gradle.jpms.JavaXConfiguration
import dorkbox.gradle.jpms.SourceSetContainer2
import org.gradle.api.*
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.utils.toSetOrEmpty
import java.io.File
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties


open class StaticMethodsAndTools(private val project: Project) {
    companion object {
        /**
         * If the kotlin plugin is applied, and there is a compileKotlin task.. Then kotlin is enabled
         * NOTE: This can ONLY be called from a task, it cannot be called globally!
         */
        fun hasKotlin(project: Project, debug: Boolean = false): Boolean {
            try {
                // check if plugin is available
                project.plugins.findPlugin("org.jetbrains.kotlin.jvm") ?: return false

                if (debug) println("\t Has kotlin plugin")

                // this will check if the task exists, and throw an exception if it does not or return false
                project.tasks.named("compileKotlin", KotlinCompile::class.java).orNull ?: return false

                if (debug) println("\t Has compile kotlin task")

                // check to see if we have any kotlin file
                val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer
                val main = sourceSets.getByName("main")

                if (debug) {
                    println("\t main dirs: ${main.java.srcDirs}")
                    println("\t kotlin dirs: ${main.kotlin.srcDirs}")

                    project.buildFile.parentFile.walkTopDown().filter { it.extension == "kt" }.forEach {
                        println("\t\t$it")
                    }
                }

                val files = main.java.srcDirs + main.kotlin.srcDirs
                files.forEach { srcDir ->
                    val kotlinFile = srcDir.walkTopDown().find { it.extension == "kt" }
                    if (kotlinFile?.exists() == true) {
                        if (debug) println("\t Has kotlin file: $kotlinFile")
                        return true
                    }
                }
            } catch (e: Exception) {
                if (debug) e.printStackTrace()
            }

            return false
        }
    }

    val isUnix = org.gradle.internal.os.OperatingSystem.current().isUnix
    val isLinux = org.gradle.internal.os.OperatingSystem.current().isLinux
    val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows

    private var fixedSWT = false
    // this is lazy, because it MUST be run from a task!
    val hasKotlin: Boolean by lazy { hasKotlin(project) }

    /**
     * Maps the property (key/value) pairs of a property file onto the specified target object. Also maps fields in the targetObject to the
     * project, if have the same name relationship (ie: field name is "version", project method is "setVersion")
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
                val setterName = "set${key.capitalize()}"
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
            assignedExtraProperties.forEach {
                val propertyName = it.name
                val setterName = "set${propertyName.capitalize()}"

                val projectMethod = propertyFunctions.find { prop -> prop.name == setterName }
                if (projectMethod != null) {
                    if (it.getter.property.isConst) {
                        projectMethod.call(project, it.getter.call())
                    } else {
                        projectMethod.call(project, it.getter.call(kClass.objectInstance))
                    }
                }
            }
        }
    }

    /**
     * Validates the minimum version of gradle supported
     */
    fun minVersion(version: String) {
        val compared = org.gradle.util.GradleVersion.current().compareTo(org.gradle.util.GradleVersion.version(version))
        if (compared == -1) {
            throw GradleException("This project requires Gradle $version or higher.")
        }
    }

    /**
     * Validates the maximum version of gradle supported
     */
    fun maxVersion(version: String) {
        val compared = org.gradle.util.GradleVersion.current().compareTo(org.gradle.util.GradleVersion.version(version))
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
        project.repositories.filterIsInstance<org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository>().forEach { repo ->
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
                    } catch (e: Exception) {
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
        project.buildscript.repositories.filterIsInstance<org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository>()
            .forEach {
                repo ->
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
                    } catch (e: Exception) {
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
        fixIntellijPaths()
        fixMavenPaths()
        defaultResolutionStrategy()
        defaultCompileOptions()
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
     * Fix the compiled output from intellij to be SEPARATE from gradle.
     */
    fun fixIntellijPaths(location: String = "${project.buildDir}/classes-intellij") {
        project.allprojects.forEach { project ->
            // https://discuss.gradle.org/t/can-a-plugin-itself-add-buildscript-dependencies-and-then-apply-a-plugin/25039/4
            apply(project, "idea")

            // put idea in it's place! Not having this causes SO MANY PROBLEMS when building modules
            idea(project) {
                // https://youtrack.jetbrains.com/issue/IDEA-175172
                module {
                    val mainDir = File(location)
                    it.outputDir = mainDir
                    it.testOutputDir = mainDir

                    // by default, we ALWAYS want sources. If you have sources, you don't need javadoc (since the sources have them in it already)
                    it.isDownloadJavadoc = false
                    it.isDownloadSources = true
                }
            }

            // this has the side-effect of NOT creating the gradle directories....

//            // make sure that the source set directories all exist. THIS SHOULD NOT BE A PROBLEM!
//            project.afterEvaluate { prj ->
//                val sourceSets = prj.extensions.getByName("sourceSets") as SourceSetContainer
//                sourceSets.forEach { set ->
//                    set.output.classesDirs.forEach { dir ->
//                        println("Creating dir: ${dir.absolutePath}")
//                        if (!dir.exists()) {
//                            dir.mkdirs()
//                        }
//                    }
//                }
//            }
//
//
//            val sourceSets = project.extensions.getByName("sourceSets") as SourceSetContainer
//            sourceSets.forEach { set ->
//                val name = set.name
//
//                println("\tFixing IntelliJ sourceSet: $name")
//
//                // https://stackoverflow.com/questions/42064377/mark-gradle-source-folder-as-test-source-in-intellij
//                // https://youtrack.jetbrains.com/issue/IDEA-165647`
//                // if this is a TEST dir, add it to intellij test dirs!
//                val files = set.java.srcDirs
//                if (hasKotlin) {
//                    files.addAll(set.kotlin.srcDirs)
//                }
//
//                val absoluteFiles = files.map{it.absoluteFile}
//                absoluteFiles.forEach {
//                    println("***${it}")
//                }
//
//                if (name.toLowerCase().contains("test") ||
//                    absoluteFiles.any { it.name.toLowerCase().contains("test") }) {
//                    println("\t\tFixing IntelliJ test sourceSet: $name")
//
//                    idea(project) {
//                        module { mod ->
//                            println("MOD::: ${mod.name}")
//                            println("\t\tsource:" + mod.sourceDirs)
//                            println("\t\ttest:" + mod.testSourceDirs)
//
//                            mod.sourceDirs.removeAll(absoluteFiles)
//                            mod.testSourceDirs.addAll(absoluteFiles)
//
//                            val scopeMap = mod.scopes["TEST"]
////                                    += [ configurations.performanceTestCompile ]
//
//                            println("\t\tMod source:" + mod.sourceDirs)
//                            println("\t\tMod test:" + mod.testSourceDirs)
//                        }
//                    }
//                }
////                    }
//            }
        }
    }

    /**
     * Change the source input from the opinionated sonatype paths to a simpler directory
     */
    fun fixMavenPaths() {
        // it is SUPER annoying to use the opinionated sonatype directory structure. I don't like it. We pass in the "configuration" action
        // instead of doing it a different way, so the creation AND configuration of these sorucesets can occur. (Otherwise they won't)
        val configure: Action<SourceSetContainer> = Action {
            val main = it.named("main", org.gradle.api.tasks.SourceSet::class.java).get()
            val test = it.named("test", org.gradle.api.tasks.SourceSet::class.java).get()

            main.apply {
                java.apply {
                    setSrcDirs(project.files("src"))
                    include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...
                }

                if (hasKotlin) {
                    main.kotlin {
                        setSrcDirs(project.files("src"))
                        include("**/*.kt") // want to include java files for the source. 'setSrcDirs' resets includes...
                    }
                }

                resources.setSrcDirs(project.files("resources"))
            }

            test.apply {
                java.apply {
                    setSrcDirs(project.files("test"))
                    include("**/*.java") // want to include java files for the source. 'setSrcDirs' resets includes...
                }

                if (hasKotlin) {
                    test.kotlin {
                        setSrcDirs(project.files("test"))
                        include("**/*.kt") // want to include java files for the source. 'setSrcDirs' resets includes...
                    }
                }

                resources.setSrcDirs(project.files("testResources"))
            }
        }

        (project as org.gradle.api.plugins.ExtensionAware).extensions.configure("sourceSets", configure)
    }

    /**
     * Configure a default resolution strategy. While not necessary, this is used for enforcing sane project builds
     */
    fun defaultResolutionStrategy() {
        project.allprojects.forEach { proj ->
            proj.configurations.forEach { config ->
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
    }

    /**
     * Always compile java with UTF-8, make it incremental, and compile `package-info.java` classes
     */
    fun defaultCompileOptions() {
        project.allprojects.forEach { project ->
            project.afterEvaluate { prj ->
                prj.tasks.withType(JavaCompile::class.java) {
                    it.options.encoding = "UTF-8"
                    it.options.isIncremental = true
                    it.options.compilerArgs.add("-Xpkginfo:always")
                }
            }
        }
    }

    /**
     * Basic, default compile configurations
     */
    fun compileConfiguration(javaVersion: JavaVersion,
                             kotlinJavaVersion: JavaVersion = javaVersion,
                             kotlinActions: KotlinJvmOptions.() -> Unit = {}) {
        val javaVer = javaVersion.toString()
        val kotlinJavaVer = kotlinJavaVersion.toString()

        val kotlinVer: String = try {
            if (hasKotlin) {
                val kot = project.plugins.findPlugin("org.jetbrains.kotlin.jvm") as org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper?
                val version = kot?.kotlinPluginVersion ?: "1.4.32"

                // we ONLY care about the major.minor
                val secondDot = version.indexOf('.', version.indexOf('.')+1)
                version.substring(0, secondDot)
            } else {
                "1.4.32"
            }
        } catch (e: Exception) {
            // in case we cannot parse it from the plugin, provide a reasonable default (latest stable)
            "1.4.32"
        }

        project.allprojects.forEach { project ->
            project.tasks.withType(JavaCompile::class.java) { task ->
                task.doFirst {
                    println("\tCompiling classes to Java ${JavaVersion.toVersion(task.targetCompatibility)}")
                }

                task.options.encoding = "UTF-8"

                // -Xlint:deprecation
                task.options.isDeprecation = true

                // -Xlint:unchecked
                task.options.compilerArgs.add("-Xlint:unchecked")

                task.sourceCompatibility = javaVer
                task.targetCompatibility = javaVer
            }

            project.tasks.withType(Jar::class.java) {
                it.duplicatesStrategy = DuplicatesStrategy.FAIL
            }

            if (hasKotlin) {
                project.tasks.withType(KotlinCompile::class.java) { task ->
                    task.doFirst {
                        println("\tCompiling classes to Kotlin ${task.kotlinOptions.languageVersion}, Java ${task.kotlinOptions.jvmTarget}")
                    }

                    task.sourceCompatibility = kotlinJavaVer
                    task.targetCompatibility = kotlinJavaVer

                    task.kotlinOptions.jvmTarget = kotlinJavaVer

                    // default is whatever the version is that we are running, or 1.4.32 if we cannot figure it out
                    task.kotlinOptions.apiVersion = kotlinVer
                    task.kotlinOptions.languageVersion = kotlinVer

                    // see: https://kotlinlang.org/docs/reference/using-gradle.html
                    kotlinActions(task.kotlinOptions)
                }
            }
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

        // macoxs
        // org.eclipse.swt.cocoa.macosx.x86_64

        val currentOS = org.gradle.internal.os.OperatingSystem.current()
        val swtType = if (System.getProperty("os.arch").matches(".*64.*".toRegex())) {
            when {
                currentOS.isWindows -> SwtType.WIN_64
                currentOS.isMacOsX  -> SwtType.MAC_64
                else                -> SwtType.LINUX_64
            }
        } else {
            when {
                currentOS.isWindows -> SwtType.WIN_32
                currentOS.isMacOsX  -> SwtType.MAC_64  // not possible on mac, but here for completeness
                else                -> SwtType.LINUX_32
            }
        }

        val fullId = swtType.fullId(version)

        if (!fixedSWT) {
            fixedSWT = true

            project.allprojects.forEach { project ->
                project.configurations.all { config ->
                    config.resolutionStrategy { strat ->
                        strat.dependencySubstitution { sub ->
                            // The maven property ${osgi.platform} is not handled by Gradle for the SWT builds
                            // so we replace the dependency, using the osgi platform from the project settings
                            sub.substitute(sub.module("org.eclipse.platform:org.eclipse.swt.\${osgi.platform}"))
                                .with(sub.module(fullId))
                        }
                    }
                }
            }
        }

        return fullId
    }

    private fun idea(project: Project, configure: org.gradle.plugins.ide.idea.model.IdeaModel.() -> Unit): Unit =
            project.extensions.configure("idea", configure)

    // required to make sure the plugins are correctly applied. ONLY applying it to the project WILL NOT work.
    // The plugin must also be applied to the root project
    private fun apply(project: Project, id: String) {
        if (project.rootProject.pluginManager.findPlugin(id) == null) {
            project.rootProject.pluginManager.apply(id)
        }

        if (project.pluginManager.findPlugin(id) == null) {
            project.pluginManager.apply(id)
        }
    }


    /**
     * Load JPMS for a specific java version using the default configuration
     */
    fun jpms(javaVersion: JavaVersion): JavaXConfiguration {
        return JavaXConfiguration(javaVersion, project)
    }

    /**
     * Load and configure JPMS for a specific java version
     */
    fun jpms(javaVersion: JavaVersion, block: SourceSetContainer2.() -> Unit): JavaXConfiguration {
        val javaX = JavaXConfiguration(javaVersion, project)
        block(SourceSetContainer2(javaX))
        return javaX
    }


    /**
     * Should gradle try to infer that this project is a JPMS module by analysing JARs and the classpath?
     *
     * Only possible for gradle >= 6.4
     */
    fun inferJpmsModule() {
        if (GradleVersion.current() >= GradleVersion.version("6.4")) {
            project.gradle.taskGraph.whenReady {
                project.convention.configure(JavaPluginExtension::class.java) {
                    // Should a --module-path be inferred by analysing JARs and class folders on the classpath?
                    it.modularity.inferModulePath.set(true)
                }
            }
        }
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
        if (hasKotlin) {
            // Make sure to cleanup the any possible license file on clean
            println("\tAllowing kotlin internal access for $moduleName")

            project.tasks.withType(KotlinCompile::class.java).forEach {
                // must be the same module name as the regular one (which is the project name). If it is a different name, it crashes at runtime
                it.kotlinOptions.moduleName = moduleName
            }

            accessGroup.forEach {
                // allow code in a *different* directory access to "internal" scope members of code.
                // THIS FIXES GRADLE - BUT NOT INTELLIJ!
                val kotlinExt = project.extensions.getByName("kotlin") as org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
                kotlinExt.target.compilations.getByName(it.sourceName).apply {
                    it.targetNames.forEach { targetName ->
                        associateWith(target.compilations.getByName(targetName))
                    }
                }
            }
        }
    }

    class AccessGroup(val sourceName: String, vararg targetNames: String) {
        val targetNames: Array<out String> = targetNames
    }
}
