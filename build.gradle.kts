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

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!

plugins {
    java
    `java-gradle-plugin`

    id("com.gradle.plugin-publish") version "2.0.0"

    id("com.dorkbox.GradleUtils") version "4.4"
    id("com.dorkbox.Licensing") version "3.1"
    id("com.dorkbox.VersionUpdate") version "3.1"

    kotlin("jvm") version "2.3.0"
}

object Extras {
    // set for the project
    const val description = "Gradle Plugin to manage various Gradle tasks, such as updating gradle and dependencies"
    const val group = "com.dorkbox"
    const val version = "4.6"

    // set as project.ext
    const val name = "Gradle Utils"
    const val id = "GradleUtils"
    const val vendor = "Dorkbox LLC"
    const val url = "https://git.dorkbox.com/dorkbox/GradleUtils"
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.defaults()
GradleUtils.compileConfiguration(JavaVersion.VERSION_25)


licensing {
    license(License.APACHE_2) {
        description(Extras.description)
        author(Extras.vendor)
        url(Extras.url)
    }

    license(License.APACHE_2) {
        description("Code used from https://github.com/DanySK")
        author("Danilo Pianini")
        url("https://github.com/DanySK/publish-on-central")
        url("https://github.com/DanySK/maven-central-portal-kotlin-api")
    }
}

repositories {
    gradlePluginPortal()
}

dependencies {
    api(gradleApi())
    api(gradleKotlinDsl())

    // compile only, so we don't force kotlin/dsl version info into dependencies

    // the kotlin version is taken from the plugin, so it is not necessary to set it here
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin")

    // for easier OS identification
    implementation("com.dorkbox:OS:2.0")

    // for parsing JSON when updating gradle
    implementation("org.json:json:20251224")

    // for parsing version information from maven
    implementation("com.dorkbox:Version:3.2")
}

tasks.jar.get().apply {
    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = GradleUtils.now()
        attributes["Implementation-Vendor"] = Extras.vendor
    }
}


/////////////////////////////////
////////    Plugin Publishing + Release
/////////////////////////////////
gradlePlugin {
    website.set(Extras.url)
    vcsUrl.set(Extras.url)

    plugins {
        register("GradlePublish") {
            id = "${Extras.group}.${Extras.id}"
            implementationClass = "dorkbox.gradle.GradleUtils"
            displayName = Extras.name
            description = Extras.description
            tags.set(listOf("build", "jpms", "utilities", "update", "dependencies", "dependency management"))
            version = Extras.version
        }
    }
}

