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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the GraphQL API returns and takes for the catalogue.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    /**
     * How to order a list of albums. Ties keep the default order: newest year first, then title.
     */
    public enum AlbumSort {
        /**
         * Newest year first; the default.
         */
        YEAR_DESC,
        YEAR_ASC,
        TITLE,
        /**
         * Most recently published first.
         */
        PUBLISHED,
        /**
         * Highest weighted average rating first; unrated albums last.
         */
        RATING,
        /**
         * Most liked first.
         */
        LIKES,
        /**
         * Most played by everyone first.
         */
        PLAYS
    }

    public record AlbumDto(UUID id, String title, @Nullable Integer year, AlbumKindService.AlbumKindDto kind,
                           @Nullable String description, List<AlbumSpexDto> spex, boolean hasCover,
                           boolean published, @Nullable Instant publishedAt, @Nullable Instant trashedAt,
                           @Nullable String navidromeId, int trackCount, double durationMs, Instant createdAt,
                           @Nullable Instant lastModifiedAt) {
    }

    public record AlbumSpexDto(UUID id, @Nullable String title, @Nullable Integer year, @Nullable Boolean revival,
                               @Nullable UUID parentId, UUID familyId, @Nullable UUID categoryId,
                               @Nullable String categoryName) {
    }

    public record TrackDto(UUID id, UUID albumId, String albumTitle, int discNumber, int trackNumber,
                           String title, @Nullable String performers, @Nullable String melody,
                           @Nullable String lyricsAuthor, @Nullable String arranger, @Nullable String lyrics,
                           boolean lyricsSynced, String format, double fileSize, double durationMs,
                           @Nullable Integer bitrate, boolean published, boolean listed, @Nullable Instant trashedAt,
                           @Nullable String navidromeId, boolean fileMissing, @Nullable ReplayGainDto replayGain) {
    }

    /**
     * ReplayGain 2.0 values: gains in dB, peaks as linear amplitudes. Album values only for listed tracks.
     */
    public record ReplayGainDto(double trackGain, double trackPeak, @Nullable Double albumGain,
                                @Nullable Double albumPeak) {
    }

    public record TrackRefDto(UUID id, String title, int discNumber, int trackNumber, UUID albumId, String albumTitle,
                              @Nullable UUID spexId) {
    }

    public record CreditDto(String name, int trackCount) {
    }

    public record CategoryDto(UUID id, String name, int albumCount) {
    }

    public record SearchResultDto(List<AlbumDto> albums, List<TrackDto> tracks) {
    }

    public record TrashDto(List<AlbumDto> albums, List<TrackDto> tracks) {
    }

    /**
     * What to list of the albums. {@code fromYear} and {@code toYear} include their years; {@code liked} is about the
     * caller.
     */
    public record AlbumFilter(@Nullable UUID categoryId, @Nullable Integer year, @Nullable UUID spexId,
                              @Nullable Boolean includeUnpublished, @Nullable UUID kindId, @Nullable Integer fromYear,
                              @Nullable Integer toYear, @Nullable Boolean liked, @Nullable AlbumSort sort) {

        public AlbumFilter(final @Nullable UUID categoryId, final @Nullable Integer year, final @Nullable UUID spexId,
                           final @Nullable Boolean includeUnpublished) {
            this(categoryId, year, spexId, includeUnpublished, null, null, null, null, null);
        }
    }

    public record AlbumInput(@Nullable List<UUID> spexIds, @Nullable String title, @Nullable Integer year,
                             @Nullable UUID kindId,
                             @Nullable String description) {
    }

    public record TrackInput(int discNumber, int trackNumber, String title, @Nullable String performers,
                             @Nullable String melody, @Nullable String lyricsAuthor, @Nullable String arranger,
                             @Nullable String lyrics, boolean lyricsSynced) {
    }
}
