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

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexify.server.util.AbstractAuditable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The songs of one spex, usually one recording of it. Its uuid names its folders and is written into the tags as
 * MusicBrainz album id, which Navidrome derives its persistent album id from.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "album")
@Getter
@Setter
public class Album extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid = UUID.randomUUID().toString();

    @Embedded
    private SpexRef spex = new SpexRef();

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "year")
    private @Nullable Integer year;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private AlbumKind kind = AlbumKind.SHOW;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "cover_extension")
    private @Nullable String coverExtension;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    @Column(name = "navidrome_id")
    private @Nullable String navidromeId;

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL)
    @OrderBy("discNumber ASC, trackNumber ASC")
    private List<Track> tracks = new ArrayList<>();

    public boolean isTrashed() {
        return trashedAt != null;
    }

    /**
     * Visible to listeners: published and not in the trash.
     */
    public boolean isListed() {
        return published && trashedAt == null;
    }
}
