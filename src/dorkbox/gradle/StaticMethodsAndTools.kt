package dorkbox.gradle

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties


open class StaticMethodsAndTools(private val project: Project) {
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
     * Resolves all child dependencies of the project
     *
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun resolveDependencies(): List<DependencyScanner.Dependency> {
        val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
        val existingNames = mutableSetOf<String>()

        DependencyScanner.scan(project, "compileClasspath", projectDependencies, existingNames)
        DependencyScanner.scan(project, "runtimeClasspath", projectDependencies, existingNames)

        return projectDependencies
    }

    /**
     * Resolves all child compile dependencies of the project
     *
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun resolveCompileDependencies(): List<DependencyScanner.Dependency> {
        val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
        val existingNames = mutableSetOf<String>()

        DependencyScanner.scan(project, "compileClasspath", projectDependencies, existingNames)

        return projectDependencies
    }

    /**
     * Resolves all child compile dependencies of the project
     *
     * THIS MUST BE IN "afterEvaluate" or run from a specific task.
     */
    fun resolveRuntimeDependencies(): List<DependencyScanner.Dependency> {
        val projectDependencies = mutableListOf<DependencyScanner.Dependency>()
        val existingNames = mutableSetOf<String>()

        DependencyScanner.scan(project, "runtimeClasspath", projectDependencies, existingNames)

        return projectDependencies
    }

    /**
     * Fix the compiled output from intellij to be SEPARATE from gradle.
     */
    fun fixIntellijPaths(location: String = "${project.buildDir}/classes-intellij") {
        // https://discuss.gradle.org/t/can-a-plugin-itself-add-buildscript-dependencies-and-then-apply-a-plugin/25039/4
        apply(project, "idea")

        // put idea in it's place! Not having this causes SO MANY PROBLEMS when building modules
        idea(project) {
            // https://youtrack.jetbrains.com/issue/IDEA-175172
            module {
                val mainDir = File(location)
                it.outputDir = mainDir
                it.testOutputDir = mainDir
            }
        }

        // this has the side-effect of NOT creating the gradle directories....

        // make sure that the source set directories all exist. THIS SHOULD NOT BE A PROBLEM!
        project.afterEvaluate { prj ->
            prj.allprojects.forEach { proj ->
                val javaPlugin: JavaPluginConvention = proj.convention.getPlugin(JavaPluginConvention::class.java)
                val sourceSets = javaPlugin.sourceSets

                sourceSets.forEach { set ->
                    set.output.classesDirs.forEach { dir ->
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }
                    }
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
     * Basic, default compile configurations
     */
    fun compileConfiguration(javaVersion: JavaVersion, kotlinActions: (KotlinJvmOptions) -> Unit = {}) {
        val javaVer = javaVersion.toString()

        project.tasks.withType(JavaCompile::class.java) { task ->
            task.doFirst {
                println("\tCompiling classes to Java $javaVersion")
            }
            task.options.encoding = "UTF-8"

            task.sourceCompatibility = javaVer
            task.targetCompatibility = javaVer
        }

        project.tasks.withType(Jar::class.java) {
            it.duplicatesStrategy = DuplicatesStrategy.FAIL
        }

        project.tasks.withType(KotlinCompile::class.java) { task ->
            task.doFirst {
                println("\tCompiling classes to Kotlin ${task.kotlinOptions.languageVersion}, Java ${task.kotlinOptions.jvmTarget}")
            }

            task.sourceCompatibility = javaVer
            task.targetCompatibility = javaVer

            task.kotlinOptions.jvmTarget = javaVer

            // default is 1.3
            task.kotlinOptions.apiVersion = "1.3"
            task.kotlinOptions.languageVersion = "1.3"

            // see: https://kotlinlang.org/docs/reference/using-gradle.html
            kotlinActions(task.kotlinOptions)
        }
    }

    private var fixedSWT = false


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
        val windowingTk = when {
            currentOS.isWindows -> "win32"
            currentOS.isMacOsX  -> "cocoa"
            else                -> "gtk"
        }

        val platform = when {
            currentOS.isWindows -> "win32"
            currentOS.isMacOsX  -> "macosx"
            else                -> "linux"
        }


        var arch = System.getProperty("os.arch")
        arch = when {
            arch.matches(".*64.*".toRegex()) -> "x86_64"
            else                             -> "x86"
        }

        val mavenId = "$windowingTk.$platform.$arch"

        if (!fixedSWT) {
            fixedSWT = true

            project.configurations.all { config ->
                config.resolutionStrategy { strat ->
                    strat.dependencySubstitution { sub ->
                        // The maven property ${osgi.platform} is not handled by Gradle for the SWT builds
                        // so we replace the dependency, using the osgi platform from the project settings
                        sub.substitute(sub.module("org.eclipse.platform:org.eclipse.swt.\${osgi.platform}"))
                            .with(sub.module("org.eclipse.platform:org.eclipse.swt.$mavenId:$version"))
                    }
                }
            }
        }

        return "org.eclipse.platform:org.eclipse.swt.$mavenId:$version"
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
}
