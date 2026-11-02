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

import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumInput;
import nu.fgv.spexify.server.catalog.CatalogDtos.CategoryDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.SearchResultDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackInput;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackRefDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrashDto;
import nu.fgv.spexify.server.spex.SpexDto;
import nu.fgv.spexify.server.spex.SpexService;
import nu.fgv.spexify.server.spexflix.VideoLinkService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for the catalogue. A thin adapter: {@link CatalogService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class CatalogGraphqlApi {

    private final CatalogService service;
    private final SpexService spexService;
    private final VideoLinkService videoLinkService;

    public CatalogGraphqlApi(final CatalogService service, final SpexService spexService, final VideoLinkService videoLinkService) {
        this.service = service;
        this.spexService = spexService;
        this.videoLinkService = videoLinkService;
    }

    @QueryMapping
    public List<AlbumDto> albums(@Argument final @Nullable AlbumFilter filter) {
        return service.albums(filter);
    }

    @QueryMapping
    public @Nullable AlbumDto album(@Argument final UUID id) {
        return service.album(id);
    }

    @QueryMapping
    public List<AlbumDto> recentAlbums(@Argument final @Nullable Integer limit) {
        return service.recent(limit == null ? 12 : limit);
    }

    @QueryMapping
    public List<CategoryDto> categories() {
        return service.categories();
    }

    @QueryMapping
    public List<Integer> years() {
        return service.years();
    }

    @QueryMapping
    public SearchResultDto search(@Argument final String q) {
        return service.search(q);
    }

    @QueryMapping
    public List<SpexDto> spexSearch(@Argument final String q) {
        return spexService.search(q);
    }

    @QueryMapping
    public TrashDto trash() {
        return service.trash();
    }

    @QueryMapping
    public List<TrackRefDto> spexTracks(@Argument final UUID spexId) {
        return service.spexTracks(spexId);
    }

    @QueryMapping
    public List<TrackDto> tracksByNavidromeIds(@Argument final List<String> ids) {
        return service.tracksByNavidromeIds(ids);
    }

    @QueryMapping
    public List<AlbumDto> albumsByNavidromeIds(@Argument final List<String> ids) {
        return service.albumsByNavidromeIds(ids);
    }

    @QueryMapping
    public List<TrackRefDto> tracksByIds(@Argument final List<UUID> ids) {
        return service.tracksByIds(ids);
    }

    @SchemaMapping(typeName = "Album", field = "tracks")
    public List<TrackDto> tracks(final AlbumDto album) {
        return service.tracks(album.id());
    }

    @BatchMapping(typeName = "Track", field = "videoLinks")
    public Map<TrackDto, List<VideoLinkService.VideoLinkDto>> videoLinks(final List<TrackDto> tracks) {
        final Map<UUID, List<VideoLinkService.VideoLinkDto>> links = videoLinkService.links(tracks.stream().map(TrackDto::id).toList());
        final Map<TrackDto, List<VideoLinkService.VideoLinkDto>> result = new java.util.LinkedHashMap<>();

        tracks.forEach(track -> result.put(track, links.getOrDefault(track.id(), List.of())));
        return result;
    }

    @MutationMapping
    public AlbumDto albumCreate(@Argument final AlbumInput input) {
        return service.albumCreate(input);
    }

    @MutationMapping
    public AlbumDto albumUpdate(@Argument final UUID id, @Argument final AlbumInput input) {
        return service.albumUpdate(id, input);
    }

    @MutationMapping
    public AlbumDto albumPublish(@Argument final UUID id, @Argument final boolean published) {
        return service.albumPublish(id, published);
    }

    @MutationMapping
    public AlbumDto albumTrash(@Argument final UUID id) {
        return service.albumTrash(id, true);
    }

    @MutationMapping
    public AlbumDto albumRestore(@Argument final UUID id) {
        return service.albumTrash(id, false);
    }

    @MutationMapping
    public @Nullable Void albumDelete(@Argument final UUID id) {
        service.albumDelete(id);
        return null;
    }

    @MutationMapping
    public AlbumDto albumCoverRemove(@Argument final UUID id) {
        return service.albumCoverRemove(id);
    }

    @MutationMapping
    public AlbumDto tracksReorder(@Argument final UUID albumId, @Argument final List<UUID> trackIds) {
        return service.tracksReorder(albumId, trackIds);
    }

    @MutationMapping
    public TrackDto trackCreate(@Argument final UUID albumId, @Argument final String uploadId) {
        return service.trackCreate(albumId, uploadId);
    }

    @MutationMapping
    public TrackDto trackUpdate(@Argument final UUID id, @Argument final TrackInput input) {
        return service.trackUpdate(id, input);
    }

    @MutationMapping
    public TrackDto trackReplaceAudio(@Argument final UUID id, @Argument final String uploadId) {
        return service.trackReplaceAudio(id, uploadId);
    }

    @MutationMapping
    public TrackDto trackPublish(@Argument final UUID id, @Argument final boolean published) {
        return service.trackPublish(id, published);
    }

    @MutationMapping
    public TrackDto trackTrash(@Argument final UUID id) {
        return service.trackTrash(id, true);
    }

    @MutationMapping
    public TrackDto trackRestore(@Argument final UUID id) {
        return service.trackTrash(id, false);
    }

    @MutationMapping
    public @Nullable Void trackDelete(@Argument final UUID id) {
        service.trackDelete(id);
        return null;
    }

    @MutationMapping
    public @Nullable Void trashEmpty() {
        service.trashEmpty();
        return null;
    }
}
