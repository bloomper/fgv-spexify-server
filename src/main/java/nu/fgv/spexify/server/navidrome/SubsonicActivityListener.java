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

import java.util.Map;
import java.util.Set;

/**
 * Told about every Subsonic request that changed what a user has done with the music and that Navidrome accepted:
 * ratings, likes and plays, from the web or an app.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface SubsonicActivityListener {

    /**
     * The endpoints reported, in lower case.
     */
    Set<String> ENDPOINTS = Set.of("setrating", "star", "unstar", "scrobble");

    /**
     * Called after Navidrome accepted the request. Must not throw.
     *
     * @param username   who made the request
     * @param endpoint   one of {@link #ENDPOINTS}
     * @param parameters the request's parameters
     */
    void accepted(String username, String endpoint, Map<String, String[]> parameters);
}
