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
package dorkbox.gradle

import org.gradle.api.Project
import org.gradle.kotlin.dsl.get


open class Extras(val project: Project) {
    private val data: GradleData by lazy {
        val utils = (project as org.gradle.api.plugins.ExtensionAware).extensions["GradleUtils"] as StaticMethodsAndTools
        utils.data
    }

    val group: String get() = data.group
    val id: String get() = data.id
    val groupAndId: String get() = "${data.group}.${data.id}"

    val description: String get() = data.description
    val name: String get() = data.name

    val version: String get() = data.version

    val vendor: String get() = data.vendor
    val vendorUrl: String get() = data.vendorUrl

    val url: String get() = data.url

    val issueManagement: IssueManagement get() = data.issueManagement
    val developer: Developer get() = data.developer
}
