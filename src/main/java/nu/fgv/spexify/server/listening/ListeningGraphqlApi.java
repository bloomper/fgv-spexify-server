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
package nu.fgv.spexify.server.listening;

import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.listening.ListeningService.TopBy;
import nu.fgv.spexify.server.listening.ListeningService.Totals;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * GraphQL for browsing albums and for everyone's ratings, likes and plays. A thin adapter: the services decide access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ListeningGraphqlApi {

    private final ListeningService service;

    public ListeningGraphqlApi(final ListeningService service) {
        this.service = service;
    }

    private static <T> Map<T, Totals> byItem(final List<T> items, final Function<T, UUID> id, final Map<UUID, Totals> totals) {
        final Map<T, Totals> result = new LinkedHashMap<>();

        items.forEach(item -> result.put(item, totals.getOrDefault(id.apply(item), Totals.NONE)));
        return result;
    }

    private static <T, V> Map<T, V> map(final Map<T, Totals> totals, final Function<Totals, V> value) {
        final Map<T, V> result = new LinkedHashMap<>();

        totals.forEach((item, entry) -> result.put(item, value.apply(entry)));
        return result;
    }

    @QueryMapping
    public List<AlbumDto> albums(@Argument final @Nullable AlbumFilter filter) {
        return service.browse(filter);
    }

    @QueryMapping
    public List<AlbumDto> topAlbums(@Argument final @Nullable TopBy by, @Argument final @Nullable AlbumFilter filter,
                                    @Argument final @Nullable Integer limit) {
        return service.topAlbums(by == null ? TopBy.RATED : by, filter, limit == null ? 20 : limit);
    }

    @QueryMapping
    public List<TrackDto> topTracks(@Argument final @Nullable TopBy by, @Argument final @Nullable AlbumFilter filter,
                                    @Argument final @Nullable Integer limit) {
        return service.topTracks(by == null ? TopBy.RATED : by, filter, limit == null ? 20 : limit);
    }

    @BatchMapping(typeName = "Track", field = "averageRating")
    public Map<TrackDto, @Nullable Double> trackAverageRating(final List<TrackDto> tracks) {
        return map(trackTotals(tracks), Totals::average);
    }

    @BatchMapping(typeName = "Track", field = "ratingCount")
    public Map<TrackDto, Integer> trackRatingCount(final List<TrackDto> tracks) {
        return map(trackTotals(tracks), Totals::ratingCount);
    }

    @BatchMapping(typeName = "Track", field = "likeCount")
    public Map<TrackDto, Integer> trackLikeCount(final List<TrackDto> tracks) {
        return map(trackTotals(tracks), Totals::likeCount);
    }

    @BatchMapping(typeName = "Track", field = "totalPlays")
    public Map<TrackDto, Integer> trackTotalPlays(final List<TrackDto> tracks) {
        return map(trackTotals(tracks), entry -> (int) Math.min(Integer.MAX_VALUE, entry.playCount()));
    }

    @BatchMapping(typeName = "Album", field = "averageRating")
    public Map<AlbumDto, @Nullable Double> albumAverageRating(final List<AlbumDto> albums) {
        return map(albumTotals(albums), Totals::average);
    }

    @BatchMapping(typeName = "Album", field = "ratingCount")
    public Map<AlbumDto, Integer> albumRatingCount(final List<AlbumDto> albums) {
        return map(albumTotals(albums), Totals::ratingCount);
    }

    @BatchMapping(typeName = "Album", field = "likeCount")
    public Map<AlbumDto, Integer> albumLikeCount(final List<AlbumDto> albums) {
        return map(albumTotals(albums), Totals::likeCount);
    }

    @BatchMapping(typeName = "Album", field = "totalPlays")
    public Map<AlbumDto, Integer> albumTotalPlays(final List<AlbumDto> albums) {
        return map(albumTotals(albums), entry -> (int) Math.min(Integer.MAX_VALUE, entry.playCount()));
    }

    private Map<TrackDto, Totals> trackTotals(final List<TrackDto> tracks) {
        return byItem(tracks, TrackDto::id, service.trackTotals(tracks.stream().map(TrackDto::id).toList()));
    }

    private Map<AlbumDto, Totals> albumTotals(final List<AlbumDto> albums) {
        return byItem(albums, AlbumDto::id, service.albumTotals(albums.stream().map(AlbumDto::id).toList()));
    }
}
