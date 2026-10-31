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

package nu.fgv.spexify.server.catalog;

/**
 * Where a track's file lies under the media root. Only {@link #MUSIC} is Navidrome's music folder.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum Location {
    MUSIC("music"),
    UNPUBLISHED("unpublished"),
    TRASH("trash");

    private final String folder;

    Location(final String folder) {
        this.folder = folder;
    }

    public String folder() {
        return folder;
    }
}
