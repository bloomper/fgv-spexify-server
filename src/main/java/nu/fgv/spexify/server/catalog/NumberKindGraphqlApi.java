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

import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.spex.NumberKindDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for Spexipedia's number kinds of the tracks. A thin adapter: {@link NumberKindService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class NumberKindGraphqlApi {

    private final NumberKindService service;

    public NumberKindGraphqlApi(final NumberKindService service) {
        this.service = service;
    }

    @QueryMapping
    public List<NumberKindDto> numberKinds() {
        return service.kinds();
    }

    @BatchMapping(typeName = "NumberKind", field = "trackCount")
    public Map<NumberKindDto, Integer> trackCount(final List<NumberKindDto> kinds) {
        final Map<UUID, Integer> counts = service.trackCounts();
        final Map<NumberKindDto, Integer> result = new LinkedHashMap<>();

        kinds.forEach(kind -> result.put(kind, counts.getOrDefault(kind.id(), 0)));
        return result;
    }

    @SchemaMapping(typeName = "NumberKind", field = "tracks")
    public List<TrackDto> tracks(final NumberKindDto kind, @Argument final @Nullable Integer limit) {
        return service.tracks(kind.id(), limit == null ? 20 : limit);
    }

    @BatchMapping(typeName = "Track", field = "numberKind")
    public Map<TrackDto, NumberKindDto> numberKind(final List<TrackDto> tracks) {
        final Map<UUID, NumberKindDto> kinds = service.kindsOf(tracks);
        final Map<TrackDto, NumberKindDto> result = new LinkedHashMap<>();

        tracks.forEach(track -> {
            final NumberKindDto kind = kinds.get(track.id());

            if (kind != null) {
                result.put(track, kind);
            }
        });
        return result;
    }
}
