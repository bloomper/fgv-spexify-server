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


package nu.fgv.spexify.server.sheetmusic;

import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetMusicDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code Track.sheetMusic}: the arrangement in Spexarkivet of the number the track is a recording of.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SheetMusicGraphqlApi {

    private final SheetMusicService service;

    public SheetMusicGraphqlApi(final SheetMusicService service) {
        this.service = service;
    }

    @BatchMapping(typeName = "Track", field = "sheetMusic")
    public Map<TrackDto, @Nullable SheetMusicDto> sheetMusic(final List<TrackDto> tracks) {
        final Map<UUID, @Nullable UUID> numbers = new HashMap<>();

        tracks.forEach(track -> numbers.put(track.id(), track.numberId()));

        final Map<UUID, SheetMusicDto> sheetMusic = service.forTracks(numbers);
        final Map<TrackDto, @Nullable SheetMusicDto> result = new LinkedHashMap<>();

        tracks.forEach(track -> result.put(track, sheetMusic.get(track.id())));
        return result;
    }
}
