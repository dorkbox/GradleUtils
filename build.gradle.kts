/*
 * Copyright 2018 dorkbox, llc
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
gradle.startParameter.warningMode = WarningMode.All

plugins {
    java
    `java-gradle-plugin`

    id("com.gradle.plugin-publish") version "0.12.0"

    id("com.dorkbox.Licensing") version "2.5.4"
    id("com.dorkbox.VersionUpdate") version "2.1"
    id("com.dorkbox.GradleUtils") version "1.12"

    kotlin("jvm") version "1.4.31"
}

object Extras {
    // set for the project
    const val description = "Gradle Plugin to manage various Gradle tasks, such as updating gradle and dependencies"
    const val group = "com.dorkbox"
    const val version = "1.13"

    // set as project.ext
    const val name = "Gradle Utils"
    const val id = "GradleUtils"
    const val vendor = "Dorkbox LLC"
    const val url = "https://git.dorkbox.com/dorkbox/GradleUtils"
    val tags = listOf("gradle", "update", "dependencies", "dependency management")
    val buildDate = Instant.now().toString()
}

///////////////////////////////
/////  assign 'Extras'
///////////////////////////////
GradleUtils.load("$projectDir/../../gradle.properties", Extras)
GradleUtils.fixIntellijPaths()
GradleUtils.defaultResolutionStrategy()
GradleUtils.compileConfiguration(JavaVersion.VERSION_1_8)

licensing {
    license(License.APACHE_2) {
        description(Extras.description)
        author(Extras.vendor)
        url(Extras.url)
    }
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))

            // want to include kotlin files for the source. 'setSrcDirs' resets includes...
            include("**/*.kt")
        }
    }
}

repositories {
    jcenter()
    maven {
        url = uri("https://plugins.gradle.org/m2/")
    }
}

dependencies {
    // compile only, so we dont force kotlin version info into dependencies
    // the kotlin version is taken from the plugin, so it is not necessary to set it here
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin")

    // setup checking for the latest version of a plugin or dependency
    implementation("com.github.ben-manes:gradle-versions-plugin:0.38.0")

    // for parsing JSON
    implementation("org.json:json:20210307")
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
        create("GradleUtils") {
            id = "${Extras.group}.${Extras.id}"
            implementationClass = "dorkbox.gradle.GradleUtils"
        }
    }
}

pluginBundle {
    website = Extras.url
    vcsUrl = Extras.url

    (plugins) {
        "GradleUtils" {
            id = "${Extras.group}.${Extras.id}"
            displayName = Extras.name
            description = Extras.description
            tags = Extras.tags
            version = Extras.version
        }
    }
}
