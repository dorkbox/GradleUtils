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

package dorkbox.gradle.jpms

import org.gradle.api.tasks.SourceSet

class SourceSetContainer2(private val javaX: JavaXConfiguration) {
    fun main(block: SourceSet.() -> Unit) {
        block(javaX.mainX)
    }

    fun test(block: SourceSet.() -> Unit) {
        block(javaX.testX)
    }
}
