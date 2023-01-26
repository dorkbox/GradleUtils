package dorkbox.gradle

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

class KotlinUtil {
    companion object {
        const val defaultKotlinVersion = "1.7"

        fun getKotlinVersion(project: Project): String {
            return try {
                try {
                    val version = project.getKotlinPluginVersion()

                    // we ONLY care about the major.minor
                    val secondDot = version.indexOf('.', version.indexOf('.')+1)
                    version.substring(0, secondDot)
                } catch (ignored: Exception) {
                    defaultKotlinVersion
                }
            } catch (e: Exception) {
                // in case we cannot parse it from the plugin, provide a reasonable default (the latest stable)
                defaultKotlinVersion
            }
        }
    }
}
