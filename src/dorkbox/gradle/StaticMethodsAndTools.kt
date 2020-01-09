package dorkbox.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.declaredMemberProperties

open class StaticMethodsAndTools(private val project: Project) {
    /**
     * Maps the property (key/value) pairs of a property file onto the specified target object.
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
            props.forEach { (k, v) -> run {
                val key = k as String
                val value = v as String

                val member = extraProperties.find { it.name == key }
                if (member != null) {
                    member.setter.call(kClass.objectInstance, value)
                }
                else {
                    project.extensions.extraProperties.set(k, v)
                }
            }}
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
}
