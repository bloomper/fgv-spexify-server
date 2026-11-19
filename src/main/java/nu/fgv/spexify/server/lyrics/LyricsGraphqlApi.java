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

import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lyrics: {@code Track.lyricsRevisions}, {@code Track.singers}, {@code Track.waveform} and the mutations that save
 * and restore lyrics.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class LyricsGraphqlApi {

    private final LyricsService service;

    public LyricsGraphqlApi(final LyricsService service) {
        this.service = service;
    }

    @BatchMapping(typeName = "Track", field = "lyricsRevisions")
    public Map<TrackDto, List<LyricsService.RevisionDto>> lyricsRevisions(final List<TrackDto> tracks) {
        final Map<UUID, List<LyricsService.RevisionDto>> revisions = service.revisions(tracks.stream().map(TrackDto::id).toList());
        final Map<TrackDto, List<LyricsService.RevisionDto>> result = new LinkedHashMap<>();

        tracks.forEach(track -> result.put(track, revisions.getOrDefault(track.id(), List.of())));
        return result;
    }

    @BatchMapping(typeName = "Track", field = "singers")
    public Map<TrackDto, List<LyricsService.SingerDto>> singers(final List<TrackDto> tracks) {
        final Map<UUID, @Nullable String> lyrics = new HashMap<>();

        tracks.forEach(track -> lyrics.put(track.id(), track.lyrics()));

        final Map<UUID, List<LyricsService.SingerDto>> singers = service.singers(lyrics);
        final Map<TrackDto, List<LyricsService.SingerDto>> result = new LinkedHashMap<>();

        tracks.forEach(track -> result.put(track, singers.getOrDefault(track.id(), List.of())));
        return result;
    }

    @SchemaMapping(typeName = "Track", field = "waveform")
    public List<Double> waveform(final TrackDto track, @Argument final @Nullable Integer points) {
        return service.waveform(track.id(), points == null ? 1000 : points);
    }

    @MutationMapping
    public TrackDto trackLyricsUpdate(@Argument final UUID id, @Argument final @Nullable String lyrics, @Argument final boolean synced,
                                      @Argument final @Nullable Long baseRevisionId) {
        return service.update(id, lyrics, synced, baseRevisionId);
    }

    @MutationMapping
    public TrackDto trackLyricsRestore(@Argument final UUID id, @Argument final Long revisionId) {
        return service.restore(id, revisionId);
    }
}
