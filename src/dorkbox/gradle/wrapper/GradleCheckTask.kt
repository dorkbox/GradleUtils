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

import dorkbox.gradle.wrapper.Wrapper.Companion.Status
import org.gradle.api.tasks.TaskAction

abstract class GradleCheckTask : Wrapper() {
    init {
        group = "gradle"
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        description = "Gets both the latest and currently installed Gradle versions"
    }

    @TaskAction
    fun run() {
        val state = checkGradleVersions()
        if (state == Status.SHA_MISMATCH) {
            generateWrapper()
            outputJarSha256()
        }
    }
}
