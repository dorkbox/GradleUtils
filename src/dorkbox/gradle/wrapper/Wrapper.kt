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
package dorkbox.gradle.wrapper

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.internal.file.FileLookup
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.api.tasks.wrapper.Wrapper.PathBase
import org.gradle.api.tasks.wrapper.internal.WrapperGenerator
import org.gradle.internal.UncheckedException
import org.gradle.util.GradleVersion
import org.gradle.util.internal.DefaultGradleVersion
import org.gradle.util.internal.GUtil
import org.gradle.util.internal.WrapperDistributionUrlConverter
import org.gradle.work.DisableCachingByDefault
import org.gradle.wrapper.Download
import org.gradle.wrapper.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.*
import javax.inject.Inject

@DisableCachingByDefault(because = "Updating the wrapper is not worth caching")
abstract class Wrapper : DefaultTask() {
    @get:Internal
    val releaseText: String by lazy {
        URI("https://services.gradle.org/versions/current").toURL().readText()
    }

    @get:Internal
    val remoteCurrentGradleVersion: String? by lazy {
        JSONObject(releaseText)["version"] as String?
    }

    @get:Internal
    val currentSha256Sum: String by lazy {
        sha256(GradleVersion.current().version)
    }


    fun sha256(version: String): String {
        return URI("https://services.gradle.org/distributions/gradle-${version}-wrapper.jar.sha256").toURL().readText()
    }

    fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.forEachBlock { bytes: ByteArray, bytesRead: Int ->
            md.update(bytes, 0, bytesRead)
        }
        return md.digest()
    }

    @get:Internal
    var archivePath: String = "wrapper/dists"

    @Input
    var archiveBase: PathBase = PathBase.GRADLE_USER_HOME

    @get:Internal
    var scriptFile: File = File("gradlew")
        get() {
            return this.getServices().get(FileOperations::class.java).file(field)
        }

    @get:Internal
    var jarFile: File = File("gradle/wrapper/gradle-wrapper.jar")
        get() {
            return this.getServices().get(FileOperations::class.java).file(field)
        }


    @Input
    var distributionPath: String = "wrapper/dists"

    @Input
    var distributionBase: PathBase = PathBase.PROJECT


    /**
     * The URL to download the gradle distribution from.
     *
     * <p>If not set, the download URL is the default for the specified {@link #getGradleVersion()}.
     *
     * <p>If {@link #getGradleVersion()} is not set, will return null.
     *
     * <p>The wrapper downloads a certain distribution and caches it. If your distribution base is the
     * project, you might submit the distribution to your version control system. That way no download is necessary at
     * all. This might be in particular interesting, if you provide a custom gradle snapshot to the wrapper, because you
     * don't need to provide a download server then.
     *
     * <p>The distribution url is validated before it is written to the gradle-wrapper.properties file.
     */
    @Optional
    @get:Input
    @set:Option(option = "gradle-distribution-url", description = "The URL to download the Gradle distribution from.")
    var distributionUrl: String? = null
        get() = if (field != null) field else WrapperGenerator.getDistributionUrl(DefaultGradleVersion.version(gradleVersion), this.distributionType)
        set(url) {
            this.distributionUrlConfigured = true
            field = url
        }


    /**
     * The SHA-256 hash sum of the gradle distribution.
     *
     * <p>If not set, the hash sum of the gradle distribution is not verified.
     *
     * <p>The wrapper allows for verification of the downloaded Gradle distribution via SHA-256 hash sum comparison.
     * This increases security against targeted attacks by preventing a man-in-the-middle attacker from tampering with
     * the downloaded Gradle distribution.
     *
     * @since 4.5
     */
    @Optional
    @Input
    @Option(option = "gradle-distribution-sha256-sum", description = "The SHA-256 hash sum of the gradle distribution.")
    var distributionSha256Sum: String? = null


    /**
     * The type of the Gradle distribution to be used by the wrapper. By default, this is {@link DistributionType#BIN},
     * which is the binary-only Gradle distribution without documentation.
     *
     * @see DistributionType
     */
    @Input
    @Option(option = "distribution-type", description = "The type of the Gradle distribution to be used by the wrapper.")
    var distributionType: DistributionType = DistributionType.ALL

    /**
     * The version of the gradle distribution required by the wrapper.
     * This is usually the same version of Gradle you use for building your project.
     * The following labels are allowed to specify a version: {@code latest}, {@code release-candidate}, {@code release-milestone}, {@code release-nightly}, and {@code nightly}
     *
     * <p>The resulting distribution url is validated before it is written to the gradle-wrapper.properties file.
     */
    @get:Input
    @set:Option(option = "gradle-version", description = "The version of the Gradle distribution required by the wrapper. The following labels are allowed: latest, release-candidate, release-milestone, release-nightly, and nightly.")
    var gradleVersion: String = GradleVersion.current().version

    /**
     * The network timeout specifies how many ms to wait for when the wrapper is performing network operations, such
     * as downloading the wrapper jar.
     *
     * @since 7.6
     */
    @Input
    @Optional
    val networkTimeout: Property<Int> = this.project.objects.property(Int::class.java)


    @get:OutputFile
    val batchScript: File
        get() = WrapperGenerator.getBatchScript(scriptFile)

    @get:OutputFile
    val propertiesFile: File
        get() = WrapperGenerator.getPropertiesFile(jarFile)


    @get:Internal
    val currentVersion: String by lazy {
        GradleVersion.current().version
    }

    @get:Internal
    val isCurrentVersion: Boolean
        get() = currentVersion == remoteCurrentGradleVersion


    @get:Inject
    protected abstract val fileLookup: FileLookup?


    private val isOffline: Boolean = this.project.gradle.startParameter.isOffline
    private var distributionUrlConfigured = false



    fun generateWrapper() {
        val resolver = this.fileLookup!!.getFileResolver(scriptFile.parentFile)
        val jarFileRelativePath = resolver.resolveAsRelativePath(jarFile)

        val existingProperties = if (propertiesFile.exists()) GUtil.loadProperties(propertiesFile) else null

        this.checkProperties(existingProperties)
        this.validateDistributionUrl(propertiesFile.parentFile)

        WrapperGenerator.generate(
            archiveBase,
            archivePath,
            distributionBase,
            distributionPath,
            this.getDistributionSha256Sum(existingProperties),
            propertiesFile,
            jarFile,
            jarFileRelativePath,
            scriptFile,
            batchScript,
            distributionUrl,
            true,
            networkTimeout.getOrNull()
        )
    }

    private fun checkProperties(existingProperties: Properties?) {
        val checksumProperty = existingProperties?.getProperty("distributionSha256Sum", null as String?)

        if (!this.isCurrentVersion && this.distributionSha256Sum == null && checksumProperty != null) {
            throw GradleException("gradle-wrapper.properties contains distributionSha256Sum property, but the wrapper configuration does not have one. Specify one in the wrapper task configuration or with the --gradle-distribution-sha256-sum task option")
        }
    }

    private fun validateDistributionUrl(uriRoot: File) {
        if (this.distributionUrlConfigured) {
            val url = this.distributionUrl!!
            val uri: URI = getDistributionUri(uriRoot, url)

            if (uri.scheme == "file") {
                if (!Files.exists(Paths.get(uri).toAbsolutePath(), *arrayOfNulls<LinkOption>(0))) {
                    throw UncheckedException.throwAsUncheckedException(
                        IOException(
                            String.format("Test of distribution url %s failed. Please check the values set with --gradle-distribution-url and  --gradle-version.", url)
                        ), true
                    )
                }
            }
            else if (uri.scheme.startsWith("http") && !this.isOffline) {
                try {
                    (Download(Logger(true), "gradlew", "0")).sendHeadRequest(uri)
                }
                catch (e: Exception) {
                    throw UncheckedException.throwAsUncheckedException(
                        IOException(
                            String.format(
                                "Test of distribution url %s failed. Please check the values set with --gradle-distribution-url and --gradle-version.",
                                url
                            ), e
                        ), true
                    )
                }
            }
        }
    }

    private fun getDistributionSha256Sum(existingProperties: Properties?): String? {
        return if (this.distributionSha256Sum != null) {
            this.distributionSha256Sum
        }
        else {
            if (this.isCurrentVersion && existingProperties != null) {
                existingProperties.getProperty("distributionSha256Sum", null as String?)
            }
            else null
        }
    }

    fun checkGradleVersions(): Status {
        if (remoteCurrentGradleVersion.isNullOrEmpty()) {
            println("\tUnable to detect New Gradle Version. Output json: $releaseText")
            return Status.NOT_FOUND
        }

        // Print wrapper jar location and SHA256 after update
        val sha256LocalHex: String
        if (jarFile.exists()) {
            val sha256Local = sha256(jarFile)
            sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
        } else {
            println("\tWrapper JAR location: ${jarFile.absolutePath}")
            println("\tWrapper JAR file not found!")
            sha256LocalHex = ""
        }

        if (currentVersion == remoteCurrentGradleVersion) {
            if (currentSha256Sum == sha256LocalHex) {
                println("\tGradle is already at the latest version '$remoteCurrentGradleVersion' and has a valid SHA256")
                return Status.UP_TO_DATE
            } else {
                println("\tSHA256 is invalid. Reinstalling Gradle Wrapper to v${gradleVersion}")
                println("\tLocal  v$gradleVersion SHA256: '$sha256LocalHex'")
                println("\tRemote v$gradleVersion SHA256: '$currentSha256Sum'")
                return Status.SHA_MISMATCH
            }
        }

        println("\tDetected new Gradle Version: '$remoteCurrentGradleVersion'")
        return Status.NEW_VERSION_AVAILABLE
    }

    fun outputJarSha256() {
        val sha256Local = sha256(jarFile)
        val sha256LocalHex = sha256Local.joinToString("") { "%02x".format(it) }
        println("\tGradle v$gradleVersion SHA256: '$sha256LocalHex'")
    }

    companion object {
        private fun getDistributionUri(uriRoot: File, url: String): URI {
            try {
                return WrapperDistributionUrlConverter.convertDistributionUrl(url, uriRoot)
            }
            catch (e: URISyntaxException) {
                throw GradleException("Distribution URL String cannot be parsed: $url", e)
            }
        }

        enum class Status {
            NOT_FOUND,
            UP_TO_DATE,
            SHA_MISMATCH,
            NEW_VERSION_AVAILABLE
        }
    }
}
