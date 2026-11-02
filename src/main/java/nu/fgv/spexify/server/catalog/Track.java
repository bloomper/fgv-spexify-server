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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexify.server.media.Loudness;
import nu.fgv.spexify.server.util.AbstractAuditable;
import nu.fgv.spexify.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One song and its audio file. The file is named by the track's uuid, which is also written into its tags as
 * MusicBrainz release track id, so that edits never move the file and Navidrome's id for it stays the same.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "track")
@Getter
@Setter
public class Track extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @Column(name = "disc_number", nullable = false)
    private int discNumber = 1;

    @Column(name = "track_number", nullable = false)
    private int trackNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "performers")
    private @Nullable String performers;

    @Column(name = "melody")
    private @Nullable String melody;

    @Column(name = "lyrics_author")
    private @Nullable String lyricsAuthor;

    @Column(name = "arranger")
    private @Nullable String arranger;

    @Column(name = "lyrics")
    private @Nullable String lyrics;

    @Column(name = "lyrics_synced", nullable = false)
    private boolean lyricsSynced;

    @Column(name = "file_extension", nullable = false)
    private String fileExtension;

    @Column(name = "file_format", nullable = false)
    private String fileFormat;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "file_checksum", nullable = false)
    private String fileChecksum;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "bitrate")
    private @Nullable Integer bitrate;

    @Column(name = "loudness_lufs")
    private @Nullable Double loudnessLufs;

    @Column(name = "true_peak_dbfs")
    private @Nullable Double truePeakDbfs;

    @Enumerated(EnumType.STRING)
    @Column(name = "location", nullable = false)
    private Location location = Location.UNPUBLISHED;

    @Column(name = "tags_hash")
    private @Nullable String tagsHash;

    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    @Column(name = "navidrome_id")
    private @Nullable String navidromeId;

    public boolean isTrashed() {
        return trashedAt != null;
    }

    /**
     * Where the file belongs: the trash when the track or its album is trashed, the music folder when both are
     * published, otherwise outside Navidrome's sight.
     */
    public Location desiredLocation() {
        if (isTrashed() || album.isTrashed()) {
            return Location.TRASH;
        }
        return album.isPublished() && published ? Location.MUSIC : Location.UNPUBLISHED;
    }

    /**
     * Visible to listeners: in the music folder.
     */
    public boolean isListed() {
        return desiredLocation() == Location.MUSIC;
    }

    /**
     * The measured loudness, or null before it is measured.
     */
    public @Nullable Loudness loudness() {
        return loudnessLufs == null || truePeakDbfs == null ? null : new Loudness(loudnessLufs, truePeakDbfs);
    }

    public void setLoudness(final @Nullable Loudness loudness) {
        this.loudnessLufs = loudness == null ? null : loudness.integratedLufs();
        this.truePeakDbfs = loudness == null ? null : loudness.truePeakDbfs();
    }

    /**
     * The loudness of the album's listed and measured tracks together, for album gain; null when there are none.
     */
    public @Nullable Loudness albumLoudness() {
        return Loudness.album(album.getTracks().stream()
                .filter(Track::isListed)
                .filter(track -> track.loudness() != null)
                .map(track -> new Loudness.Measured(Objects.requireNonNull(track.loudness()), track.getDurationMs()))
                .toList());
    }

    public String fileName() {
        return uuid + "." + fileExtension;
    }
}
