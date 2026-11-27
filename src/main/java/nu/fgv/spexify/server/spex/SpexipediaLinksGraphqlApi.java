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

package nu.fgv.spexify.server.spex;

import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumRepository;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.SpexRef;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What Spexipedia asks Spexify (its {@code SPEXIPEDIA} role: published albums of spex and tracks of numbers), and
 * editors linking tracks to Spexipedia's numbers.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexipediaLinksGraphqlApi {

    private static final int MAX_IDS = 500;

    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final CatalogService catalogService;
    private final SpexService spexService;
    private final CallerService callerService;
    private final SpexifyProperties properties;
    private final nu.fgv.spexify.server.media.MediaLinkSigner signer;

    public SpexipediaLinksGraphqlApi(final AlbumRepository albumRepository, final TrackRepository trackRepository,
                                     final CatalogService catalogService, final SpexService spexService,
                                     final CallerService callerService, final SpexifyProperties properties,
                                     final nu.fgv.spexify.server.media.MediaLinkSigner signer) {
        this.signer = signer;
        this.albumRepository = albumRepository;
        this.trackRepository = trackRepository;
        this.catalogService = catalogService;
        this.spexService = spexService;
        this.callerService = callerService;
        this.properties = properties;
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public SpexipediaLinks spexipediaLinks(@Argument final List<UUID> spexIds, @Argument final List<UUID> numberIds) {
        if (!callerService.current().isSpexipediaService()) {
            throw new AccessDeniedException("SPEXIPEDIA required");
        }

        final Set<UUID> spex = new HashSet<>(spexIds.subList(0, Math.min(spexIds.size(), MAX_IDS)));
        final List<UUID> numbers = numberIds.subList(0, Math.min(numberIds.size(), MAX_IDS));
        final List<AlbumLink> albums = spex.isEmpty() ? List.of() : albumRepository.findListedBySpex(spex).stream()
                .map(album -> new AlbumLink(album.getUuid(), album.getTitle(), matching(album, spex)))
                .toList();
        final List<TrackLink> tracks = numbers.isEmpty() ? List.of() : trackRepository.findListedByNumber(numbers).stream()
                .map(track -> new TrackLink(track.getUuid(), track.getTitle(), track.getNumberId(), track.getAlbum().getUuid()))
                .toList();

        return new SpexipediaLinks(albums, tracks);
    }

    /**
     * SPEXIPEDIA. The discography of spex (fgv-infra-deploy docs/data-ownership.md): their published albums with the
     * catalogue number, medium, dates and conductor, signed links to the covers, and every published track, also
     * those without audio, with the number it is a recording of. Spexipedia shows it on its spex pages.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    public List<DiscographyAlbum> spexipediaDiscography(@Argument final List<UUID> spexIds) {
        if (!callerService.current().isSpexipediaService()) {
            throw new AccessDeniedException("SPEXIPEDIA required");
        }

        final Set<UUID> spex = new HashSet<>(spexIds.subList(0, Math.min(spexIds.size(), MAX_IDS)));

        if (spex.isEmpty()) {
            return List.of();
        }
        return albumRepository.findListedBySpex(spex).stream().map(album -> discography(album, matching(album, spex), false)).toList();
    }

    /**
     * SPEXIPEDIA. A published album for a section of a spex page in Spexipedia (its spec, 4.2.1): as in the discography,
     * with signed links to the audio of its listed tracks; null for an album that is not published or is in the trash.
     * Spexipedia passes the links only to users with access to Spexify.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    public @Nullable DiscographyAlbum spexipediaAlbum(@Argument final UUID id) {
        if (!callerService.current().isSpexipediaService()) {
            throw new AccessDeniedException("SPEXIPEDIA required");
        }
        return albumRepository.findByUuid(id).filter(Album::isListed)
                .map(album -> discography(album, album.getSpex().isEmpty() ? null : album.getSpex().getFirst().getId(), true))
                .orElse(null);
    }

    private DiscographyAlbum discography(final Album album, final @Nullable UUID spexId, final boolean audio) {
        return new DiscographyAlbum(album.getUuid(), album.getTitle(),
                album.getYear(), album.getKind().getNameSv(), album.getKind().getNameEn(),
                album.getMedium() == null ? null : album.getMedium().getNameSv(),
                album.getMedium() == null ? null : album.getMedium().getNameEn(),
                album.getCatalogueNumber(), album.getReleased(), album.getRecorded(), album.getConductor(),
                spexId,
                album.getCoverExtension() == null ? null : signer.sign("albums/%s/cover".formatted(album.getUuid())).url(),
                album.getBackCoverExtension() == null ? null : signer.sign("albums/%s/back-cover".formatted(album.getUuid())).url(),
                album.getTracks().stream().filter(nu.fgv.spexify.server.catalog.Track::isInDiscography)
                        .map(track -> new DiscographyTrack(track.getUuid(), track.getSide(), track.getDiscNumber(),
                                track.getTrackNumber(), track.getTitle(), track.getNumberId(), track.hasAudio(),
                                audio && track.isListed() ? signer.sign("tracks/%s/audio".formatted(track.getUuid())).url() : null))
                        .toList());
    }

    /**
     * The album's spex among those asked for: the spex itself, else the original it is a revival of.
     */
    private static @Nullable UUID matching(final Album album, final Set<UUID> spex) {
        for (final SpexRef ref : album.getSpex()) {
            if (spex.contains(ref.getId())) {
                return ref.getId();
            }
        }
        return album.getSpex().stream().map(SpexRef::getParentId).filter(spex::contains).findFirst().orElse(null);
    }

    @QueryMapping
    public List<NumberDto> spexNumbers(@Argument final UUID spexId) {
        return spexService.numbers(spexId);
    }

    @MutationMapping
    public TrackDto trackSetNumber(@Argument final UUID id, @Argument final @Nullable UUID numberId) {
        return catalogService.trackSetNumber(id, numberId);
    }

    /**
     * The number on its spex's page in Spexipedia; null without a number, a spex or Spexipedia's address.
     */
    @SchemaMapping(typeName = "Track", field = "spexipediaUrl")
    @Transactional(readOnly = true)
    public @Nullable String spexipediaUrl(final TrackDto track) {
        final String web = properties.spexipedia().webUrl();

        if (track.numberId() == null || !StringUtils.hasText(web)) {
            return null;
        }
        return albumRepository.findByUuid(track.albumId())
                .map(Album::mainSpex)
                .map(spex -> "%s/spex/%s#number-%s".formatted(web.replaceAll("/+$", ""), spex.getId(), track.numberId()))
                .orElse(null);
    }

    public record AlbumLink(UUID id, String title, @Nullable UUID spexId) {
    }

    public record TrackLink(UUID id, String title, @Nullable UUID numberId, UUID albumId) {
    }

    public record SpexipediaLinks(List<AlbumLink> albums, List<TrackLink> tracks) {
    }

    public record DiscographyAlbum(UUID id, String title, @Nullable Integer year, String kindNameSv, String kindNameEn,
                                   @Nullable String mediumNameSv, @Nullable String mediumNameEn,
                                   @Nullable String catalogueNumber, @Nullable String released,
                                   @Nullable String recorded,
                                   @Nullable String conductor, @Nullable UUID spexId, @Nullable String coverUrl,
                                   @Nullable String backCoverUrl, List<DiscographyTrack> tracks) {
    }

    /**
     * @param audioUrl a signed link to the audio, from spexipediaAlbum only, for a listed track
     */
    public record DiscographyTrack(UUID id, @Nullable String side, int discNumber, int trackNumber, String title,
                                   @Nullable UUID numberId, boolean hasAudio, @Nullable String audioUrl) {
    }
}
