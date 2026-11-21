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

import nu.fgv.spexify.server.catalog.AlbumMediumService.AlbumMediumDto;
import nu.fgv.spexify.server.catalog.AlbumMediumService.AlbumMediumInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * GraphQL for the album media. A thin adapter: {@link AlbumMediumService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AlbumMediumGraphqlApi {

    private final AlbumMediumService service;

    public AlbumMediumGraphqlApi(final AlbumMediumService service) {
        this.service = service;
    }

    private static <T> Map<AlbumMediumDto, T> byMedium(final List<AlbumMediumDto> media, final Function<List<UUID>, Map<UUID, T>> lookup) {
        final Map<UUID, T> values = lookup.apply(media.stream().map(AlbumMediumDto::id).toList());
        final Map<AlbumMediumDto, T> result = new LinkedHashMap<>();

        media.forEach(medium -> result.put(medium, values.get(medium.id())));
        return result;
    }

    @QueryMapping
    public List<AlbumMediumDto> albumMedia() {
        return service.media();
    }

    @BatchMapping(typeName = "AlbumMedium", field = "albumCount")
    public Map<AlbumMediumDto, Integer> albumCount(final List<AlbumMediumDto> media) {
        return byMedium(media, service::albumCounts);
    }

    @MutationMapping
    public AlbumMediumDto albumMediumCreate(@Argument final AlbumMediumInput input) {
        return service.create(input);
    }

    @MutationMapping
    public AlbumMediumDto albumMediumUpdate(@Argument final UUID id, @Argument final AlbumMediumInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public List<AlbumMediumDto> albumMediaReorder(@Argument final List<UUID> ids) {
        return service.reorder(ids);
    }

    @MutationMapping
    public @Nullable Void albumMediumDelete(@Argument final UUID id) {
        service.delete(id);
        return null;
    }
}
