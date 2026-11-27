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

package nu.fgv.spexify.server.mcp;

import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumSort;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumSpexDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.SearchResultDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackRefDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.listening.ListeningService;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The catalogue over MCP, read only: searching it, listing albums, and an album or track with its metadata. Listeners
 * see published albums and tracks, editors also unpublished ones, as in the GraphQL API; nothing here gives audio,
 * downloads or covers.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class CatalogMcpTools {

    private static final String ALBUM_NOT_FOUND = "error.album.notFound";
    private static final String TRACK_NOT_FOUND = "error.track.notFound";
    private static final String SPEX_NOT_FOUND = "error.spex.notFound";

    private final CatalogService catalogService;
    private final ListeningService listeningService;
    private final McpToolSupport support;

    public CatalogMcpTools(final CatalogService catalogService, final ListeningService listeningService, final McpToolSupport support) {
        this.catalogService = catalogService;
        this.listeningService = listeningService;
        this.support = support;
    }

    @McpTool(name = "search_catalog",
            description = "Search Spexify's albums and tracks. Matches album titles, the titles of their spex and album descriptions, and track titles, performers, melodies (often the original song and artist) and lyrics; at least two characters, at most 50 albums and 50 tracks. Tracks carry their album's year and main spex.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchResult searchCatalog(
            @McpToolParam(description = "What to look for, e.g. a song title, a spex, a performer or a line of the lyrics") final String query) {
        return support.call(() -> {
            final SearchResultDto result = catalogService.search(query);
            final Map<UUID, AlbumDto> albums = new HashMap<>();

            result.albums().forEach(album -> albums.put(album.id(), album));
            return new SearchResult(
                    result.albums().stream().map(Album::of).toList(),
                    result.tracks().stream().map(track -> Track.of(track, albums.computeIfAbsent(track.albumId(), catalogService::album))).toList());
        });
    }

    @McpTool(name = "list_albums",
            description = "List albums, newest year first unless sorted otherwise, narrowed to a spex (its revivals included for an original) and/or a year or range of years. Rows hold an album's id, its spex and how many tracks it has; get_album gives its tracks.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolSupport.ListResult<Album> listAlbums(
            @McpToolParam(required = false, description = "A spex's id, from list_spex") final @Nullable String spexId,
            @McpToolParam(required = false, description = "Albums of this year") final @Nullable Integer year,
            @McpToolParam(required = false, description = "Albums from this year on") final @Nullable Integer fromYear,
            @McpToolParam(required = false, description = "Albums up to and including this year") final @Nullable Integer toYear,
            @McpToolParam(required = false, description = "YEAR_DESC (default), YEAR_ASC, TITLE, PUBLISHED (most recently published first), RATING, LIKES or PLAYS") final @Nullable AlbumSort sort,
            @McpToolParam(required = false, description = "Editors only: also unpublished albums") final @Nullable Boolean includeUnpublished) {
        return support.call(() -> new McpToolSupport.ListResult<>(listeningService.browse(new AlbumFilter(null, year,
                        McpToolSupport.optionalUuid(spexId, SPEX_NOT_FOUND), includeUnpublished, null, fromYear, toYear, null, sort))
                .stream().map(Album::of).toList()));
    }

    @McpTool(name = "get_album",
            description = "An album with its tracks in order, per disc: titles, durations, performers, melodies, lyrics authors, arrangers and the Spexipedia numbers they are recordings of. Lyrics are left out; get_track gives them.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public AlbumWithTracks getAlbum(@McpToolParam(description = "The album's id") final String albumId) {
        return support.call(() -> {
            final UUID id = McpToolSupport.uuid(albumId, ALBUM_NOT_FOUND);
            final AlbumDto album = catalogService.album(id);

            if (album == null) {
                throw new ResourceNotFoundException(ALBUM_NOT_FOUND);
            }
            return AlbumWithTracks.of(album, catalogService.tracksOf(List.of(id)).getOrDefault(id, List.of()));
        });
    }

    @McpTool(name = "get_track",
            description = "A published track's metadata: its album, year and spex, disc and track number, duration, performers, melody, lyrics author, arranger, the Spexipedia number it is a recording of, and its lyrics (LRC with timestamps when lyricsSynced). Editors find unpublished tracks with get_album.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TrackDetail getTrack(@McpToolParam(description = "The track's id") final String trackId) {
        return support.call(() -> {
            final UUID id = McpToolSupport.uuid(trackId, TRACK_NOT_FOUND);
            final TrackRefDto ref = catalogService.tracksByIds(List.of(id)).stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(TRACK_NOT_FOUND));
            final AlbumDto album = catalogService.album(ref.albumId());
            final TrackDto track = catalogService.tracksOf(List.of(ref.albumId())).getOrDefault(ref.albumId(), List.of()).stream()
                    .filter(candidate -> id.equals(candidate.id()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(TRACK_NOT_FOUND));

            if (album == null) {
                throw new ResourceNotFoundException(TRACK_NOT_FOUND);
            }
            return TrackDetail.of(track, album);
        });
    }

    /**
     * A spex an album is about, as Spexify keeps it from Spexipedia.
     */
    public record Spex(String id, @Nullable String title, @Nullable Integer year, @Nullable Boolean revival,
                       @Nullable String originalId, @Nullable String category) {

        static Spex of(final AlbumSpexDto spex) {
            return new Spex(spex.id().toString(), spex.title(), spex.year(), spex.revival(),
                    spex.parentId() == null ? null : spex.parentId().toString(), spex.categoryName());
        }
    }

    /**
     * An album: its spex, the main one first, and how many tracks it has and how long it is.
     */
    public record Album(String id, String title, @Nullable Integer year, String kind, List<Spex> spex,
                        @Nullable String description, boolean published, int trackCount, String duration) {

        static Album of(final AlbumDto album) {
            return new Album(album.id().toString(), album.title(), album.year(), album.kind().nameEn(),
                    album.spex().stream().map(Spex::of).toList(), album.description(), album.published(), album.trackCount(),
                    McpToolSupport.duration(album.durationMs()));
        }
    }

    /**
     * A track: its place on its album, its credits and the Spexipedia number it is a recording of. Year and spex are
     * its album's.
     */
    public record Track(String id, String title, String albumId, String albumTitle, @Nullable Integer year,
                        @Nullable String spex, int disc, int number, String duration, @Nullable String performers,
                        @Nullable String melody, @Nullable String lyricsAuthor, @Nullable String arranger,
                        @Nullable String spexipediaNumberId, @Nullable String spexipediaNumberTitle,
                        boolean published) {

        static Track of(final TrackDto track, final @Nullable AlbumDto album) {
            final AlbumSpexDto main = album == null || album.spex().isEmpty() ? null : album.spex().getFirst();

            return new Track(track.id().toString(), track.title(), track.albumId().toString(), track.albumTitle(),
                    album == null ? null : album.year(), main == null ? null : main.title(), track.discNumber(), track.trackNumber(),
                    McpToolSupport.duration(track.durationMs()), track.performers(), track.melody(), track.lyricsAuthor(),
                    track.arranger(), track.numberId() == null ? null : track.numberId().toString(), track.numberTitle(),
                    track.listed());
        }
    }

    /**
     * An album with its tracks.
     */
    public record AlbumWithTracks(String id, String title, @Nullable Integer year, String kind, List<Spex> spex,
                                  @Nullable String description, boolean published, String duration,
                                  List<Track> tracks) {

        static AlbumWithTracks of(final AlbumDto album, final List<TrackDto> tracks) {
            final Album summary = Album.of(album);

            return new AlbumWithTracks(summary.id(), summary.title(), summary.year(), summary.kind(), summary.spex(),
                    summary.description(), summary.published(), summary.duration(),
                    tracks.stream().map(track -> Track.of(track, album)).toList());
        }
    }

    /**
     * A track with its album's spex and its lyrics.
     */
    public record TrackDetail(Track track, List<Spex> spex, @Nullable String lyrics, boolean lyricsSynced) {

        static TrackDetail of(final TrackDto track, final AlbumDto album) {
            return new TrackDetail(Track.of(track, album), album.spex().stream().map(Spex::of).toList(), track.lyrics(),
                    track.lyricsSynced());
        }
    }

    public record SearchResult(List<Album> albums, List<Track> tracks) {
    }
}
