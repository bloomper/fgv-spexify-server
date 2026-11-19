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


package nu.fgv.spexify.server.lyrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexify.server.catalog.Track;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A saved version of a track's lyrics.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "lyrics_revision")
@Getter
@Setter
public class LyricsRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "track_id", nullable = false)
    private Track track;

    @Column(name = "lyrics")
    private @Nullable String lyrics;

    @Column(name = "lyrics_synced", nullable = false)
    private boolean synced;

    /**
     * The saver's Keycloak subject, or {@code system} for the lyrics from before the first change.
     */
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /**
     * The saver's name; null for the lyrics from before the first change.
     */
    @Column(name = "created_by_name")
    private @Nullable String createdByName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
