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

/**
 * What the GraphQL API returns and takes for the catalogue.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record AlbumDto(Long id, String uuid, String title, @Nullable Integer year, AlbumKind kind,
                           @Nullable String description, @Nullable AlbumSpexDto spex, boolean hasCover,
                           boolean published, @Nullable Instant publishedAt, @Nullable Instant trashedAt,
                           @Nullable String navidromeId, int trackCount, double durationMs, Instant createdAt,
                           @Nullable Instant lastModifiedAt) {
    }

    public record AlbumSpexDto(Long id, @Nullable String title, @Nullable Integer year, @Nullable Boolean revival,
                               @Nullable Long categoryId, @Nullable String categoryName) {
    }

    public record TrackDto(Long id, String uuid, Long albumId, String albumTitle, int discNumber, int trackNumber,
                           String title, @Nullable String performers, @Nullable String melody,
                           @Nullable String lyricsAuthor, @Nullable String arranger, @Nullable String lyrics,
                           boolean lyricsSynced, String format, double fileSize, double durationMs,
                           @Nullable Integer bitrate, boolean published, boolean listed, @Nullable Instant trashedAt,
                           @Nullable String navidromeId, boolean fileMissing) {
    }

    public record TrackRefDto(Long id, String title, int discNumber, int trackNumber, Long albumId, String albumTitle,
                              @Nullable Long spexId) {
    }

    public record CategoryDto(Long id, String name, int albumCount) {
    }

    public record SearchResultDto(java.util.List<AlbumDto> albums, java.util.List<TrackDto> tracks) {
    }

    public record TrashDto(java.util.List<AlbumDto> albums, java.util.List<TrackDto> tracks) {
    }

    public record AlbumFilter(@Nullable Long categoryId, @Nullable Integer year, @Nullable Long spexId,
                              @Nullable Boolean includeUnpublished) {
    }

    public record AlbumInput(@Nullable Long spexId, @Nullable String title, @Nullable Integer year, AlbumKind kind,
                             @Nullable String description) {
    }

    public record TrackInput(int discNumber, int trackNumber, String title, @Nullable String performers,
                             @Nullable String melody, @Nullable String lyricsAuthor, @Nullable String arranger,
                             @Nullable String lyrics, boolean lyricsSynced) {
    }
}
