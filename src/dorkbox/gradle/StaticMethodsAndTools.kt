package dorkbox.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
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
                // fail eagerly on version conflict (includes transitive dependencies)
                // e.g. multiple different versions of the same dependency (group and name are equal)
                it.failOnVersionConflict()

                // if there is a version we specified, USE THAT VERSION (over transitive versions)
                it.preferProjectModules()

                // cache dynamic versions for 10 minutes
                it.cacheDynamicVersionsFor(10 * 60, "seconds")

                // don't cache changing modules at all
                it.cacheChangingModulesFor(0, "seconds")
            }
        }
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
