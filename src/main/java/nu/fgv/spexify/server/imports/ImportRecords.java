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

package nu.fgv.spexify.server.imports;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The records Spexify's import API accepts (spec 5.6). Album kinds are given by name or alias; spex and numbers by
 * their Spexipedia ids; a track's album by the source key of an album record.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ImportRecords {

    private ImportRecords() {
    }

    public record FileRef(String sha256, @Nullable String name) {
    }

    public record Batch(@Nullable Boolean createMissingKinds, @Nullable List<ImportAlbum> albums,
                        @Nullable List<ImportTrack> tracks) {
    }

    /**
     * An album. The catalogue number, medium (by name or alias), dates, conductor and back cover make it an entry of the
     * discography.
     */
    public record ImportAlbum(String sourceKey, @Nullable List<UUID> spexIds, @Nullable String kind,
                              @Nullable String title,
                              @Nullable Integer year, @Nullable String description, @Nullable FileRef cover,
                              @Nullable String catalogueNumber, @Nullable String medium, @Nullable String released,
                              @Nullable String recorded, @Nullable String conductor, @Nullable FileRef backCover) {
    }

    /**
     * A track. Without a file it is an entry of the discography only, until its audio is imported or uploaded. The
     * melody, lyrics author and arranger are taken only for a track without a Spexipedia number.
     */
    public record ImportTrack(String sourceKey, String albumSourceKey, @Nullable Integer discNumber,
                              @Nullable Integer trackNumber,
                              String title, @Nullable UUID numberId, @Nullable String performers,
                              @Nullable String melody,
                              @Nullable String lyricsAuthor, @Nullable String arranger, @Nullable String lyrics,
                              @Nullable Boolean lyricsSynced, @Nullable FileRef file, @Nullable String side) {
    }
}
