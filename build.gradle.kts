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
import java.time.Instant

gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS   // always show the stacktrace!

plugins {
    java
    `java-gradle-plugin`

    id("com.gradle.plugin-publish") version "1.1.0"

    id("com.dorkbox.Licensing") version "2.20"
    id("com.dorkbox.VersionUpdate") version "2.6"
    id("com.dorkbox.GradleUtils") version "3.10"

    kotlin("jvm") version "1.7.20"
}

object Extras {
    // set for the project
    const val description = "Gradle Plugin to manage various Gradle tasks, such as updating gradle and dependencies"
    const val group = "com.dorkbox"
    const val version = "3.11"

    // set as project.ext
    const val name = "Gradle Utils"
    const val id = "GradleUtils"
    const val vendor = "Dorkbox LLC"
    const val url = "https://git.dorkbox.com/dorkbox/GradleUtils"

    val buildDate = Instant.now().toString()
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.defaults()
GradleUtils.compileConfiguration(JavaVersion.VERSION_1_8)


licensing {
    license(License.APACHE_2) {
        description(Extras.description)
        author(Extras.vendor)
        url(Extras.url)
    }
}

repositories {
    gradlePluginPortal()
}

dependencies {
    // compile only, so we don't force kotlin/dsl version info into dependencies

    // the kotlin version is taken from the plugin, so it is not necessary to set it here
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin")

    // for easier OS identification
    implementation("com.dorkbox:OS:1.6")

    // for parsing JSON
    implementation("org.json:json:20220924")

    // for parsing version information from maven
    implementation("com.dorkbox:Version:3.0")
}

tasks.jar.get().apply {
    manifest {
        // https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
        attributes["Name"] = Extras.name

        attributes["Specification-Title"] = Extras.name
        attributes["Specification-Version"] = Extras.version
        attributes["Specification-Vendor"] = Extras.vendor

        attributes["Implementation-Title"] = "${Extras.group}.${Extras.id}"
        attributes["Implementation-Version"] = Extras.buildDate
        attributes["Implementation-Vendor"] = Extras.vendor
    }
}


/////////////////////////////////
////////    Plugin Publishing + Release
/////////////////////////////////
gradlePlugin {
    plugins {
        create("GradlePublish") {
            id = "${Extras.group}.${Extras.id}"
            implementationClass = "dorkbox.gradle.GradleUtils"
            displayName = Extras.name
            description = Extras.description
            version = Extras.version
        }
    }
}

pluginBundle {
    website = Extras.url
    vcsUrl = Extras.url
    tags = listOf("build", "jpms", "utilities", "update", "dependencies", "dependency management")
}
