/*
 * Copyright (c) 2020 New Vector Ltd
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.send

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import javax.inject.Inject

/**
 * This class convert a text to an html text
 * This class is tested by [MarkdownParserTest].
 * If any change is required, please add a test covering the problem and make sure all the tests are still passing.
 */
internal class MarkdownParser @Inject constructor(
        private val parser: Parser,
        private val htmlRenderer: HtmlRenderer
) {

    private val mdSpecialChars = "[`_\\-*>.\\[\\]#~]".toRegex()

    fun parse(text: String): TextContent {
        // If no special char are detected, just return plain text
        if (text.contains(mdSpecialChars).not()) {
            return TextContent(text)
        }

        val document = parser.parse(text)
        val htmlText = htmlRenderer.render(document)

        // Cleanup extra paragraph
        val cleanHtmlText = if (htmlText.lastIndexOf("<p>") == 0) {
            htmlText.removeSurrounding("<p>", "</p>\n")
        } else {
            htmlText
        }

        return if (isFormattedTextPertinent(text, cleanHtmlText)) {
            // According to https://matrix.org/docs/spec/client_server/latest#m-room-message-msgtypes:
            // The plain text version of the HTML should be provided in the body.
            TextContent(text, cleanHtmlText.trim())
        } else {
            TextContent(text)
        }
    }

    private fun isFormattedTextPertinent(text: String, htmlText: String?) =
            text != htmlText && htmlText != "<p>${text.trim()}</p>\n"
}
