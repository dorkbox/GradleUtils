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
    private val utils: StaticMethodsAndTools by lazy {
        (project as org.gradle.api.plugins.ExtensionAware).extensions["GradleUtils"] as StaticMethodsAndTools
    }

    val description: String
        get() = utils.data.description
    val group: String
        get() = utils.data.group
    val version: String
        get() = utils.data.version
    val name: String
        get() = utils.data.name
    val id: String
        get() = utils.data.id
    val vendor: String
        get() = utils.data.vendor
    val url: String
        get() = utils.data.url
    val tags: List<String>
        get() = utils.data.tags
    val groupAndId: String
        get() = "${utils.data.group}.${utils.data.id}"
}
