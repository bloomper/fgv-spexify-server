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
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexify.server.util.AbstractAuditable;
import nu.fgv.spexify.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The songs of a recording, usually of one spex but possibly of several or none. Its uuid names its folders and is written into the tags as
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
    private UUID uuid = Uuids.v7();

    /**
     * The spex the album is about, the main one first; empty when it is not about any spex.
     */
    @ElementCollection
    @CollectionTable(name = "album_spex", joinColumns = @JoinColumn(name = "album_id"))
    @OrderColumn(name = "position")
    private List<SpexRef> spex = new ArrayList<>();

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "year")
    private @Nullable Integer year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kind_id", nullable = false)
    private AlbumKind kind;

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

    /**
     * The main spex, which the poster, the default title and year and the category come from.
     */
    public @Nullable SpexRef mainSpex() {
        return spex.isEmpty() ? null : spex.getFirst();
    }

    /**
     * Whether the album is about the spex or, when it is an original, one of its revivals.
     */
    public boolean isAbout(final UUID spexId) {
        return spex.stream().anyMatch(ref -> spexId.equals(ref.getId()) || spexId.equals(ref.getParentId()));
    }

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
