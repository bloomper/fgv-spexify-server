/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexify.server.navidrome;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Subsonic error responses, in the format the client asked for, so that apps say "wrong username or password" rather
 * than "server unreachable". Subsonic answers errors with HTTP 200.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class SubsonicResponses {

    public static final int GENERIC = 0;
    public static final int MISSING_PARAMETER = 10;
    public static final int WRONG_CREDENTIALS = 40;
    public static final int NOT_AUTHORIZED = 50;
    public static final int NOT_FOUND = 70;
    /**
     * OpenSubsonic: more than one way to authenticate was given.
     */
    public static final int CONFLICTING_AUTHENTICATION = 43;
    /**
     * OpenSubsonic: the API key is not valid.
     */
    public static final int INVALID_API_KEY = 44;

    private SubsonicResponses() {
    }

    public static void error(final HttpServletResponse response, final String format, final int code, final String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        final String body;

        if ("json".equals(format)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            body = """
                    {"subsonic-response":{"status":"failed","version":"%s","type":"spexify","openSubsonic":true,"error":{"code":%d,"message":"%s"}}}"""
                    .formatted(NavidromeClient.API_VERSION, code, message.replace("\\", "\\\\").replace("\"", "\\\""));
        } else {
            response.setContentType(MediaType.TEXT_XML_VALUE);
            body = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <subsonic-response xmlns="http://subsonic.org/restapi" status="failed" version="%s" type="spexify" openSubsonic="true"><error code="%d" message="%s"/></subsonic-response>"""
                    .formatted(NavidromeClient.API_VERSION, code, HtmlUtils.htmlEscape(message));
        }
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
