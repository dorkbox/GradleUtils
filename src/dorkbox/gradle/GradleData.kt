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

class GradleData {
    var group: String = ""
    var id: String = ""
    val groupAndId: String
        get() = "${group}.${id}"


    var description: String = ""
    var name: String = ""

    var version: String = "1.0"

    var vendor: String = ""
    var vendorUrl: String = ""

    var url: String = ""

    val issueManagement: IssueManagement = IssueManagement()
    fun issueManagement(action: IssueManagement.() -> Unit) {
        action(issueManagement)
    }

    var developer: Developer = Developer()
    fun developer(action: Developer.() -> Unit) {
        action(developer)
    }
}

class IssueManagement() {
    var nickname = ""
    var url = ""
}

class Developer {
    var id = ""
    var name = ""
    var email = ""
}
