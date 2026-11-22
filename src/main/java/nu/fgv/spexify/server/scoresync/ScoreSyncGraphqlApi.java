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


package nu.fgv.spexify.server.scoresync;

import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.ScoreSyncDto;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.ScoreSyncInput;
import nu.fgv.spexify.server.util.StoredText;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Score sync maps: {@code Track.scoreSyncs} and the mutations that make them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ScoreSyncGraphqlApi {

    private final ScoreSyncService service;
    private final MessageSource messages;

    public ScoreSyncGraphqlApi(final ScoreSyncService service, final MessageSource messages) {
        this.service = service;
        this.messages = messages;
    }

    /** Why the automatic sync failed, in the reader's language. */
    @SchemaMapping(typeName = "ScoreSync", field = "error")
    public @Nullable String error(final ScoreSyncDto sync, final Locale locale) {
        return sync.error() == null ? null : StoredText.decode(sync.error()).in(messages, locale);
    }

    @BatchMapping(typeName = "Track", field = "scoreSyncs")
    public Map<TrackDto, List<ScoreSyncDto>> scoreSyncs(final List<TrackDto> tracks) {
        final Map<UUID, List<ScoreSyncDto>> maps = service.forTracks(tracks.stream().map(TrackDto::id).toList());
        final Map<TrackDto, List<ScoreSyncDto>> result = new LinkedHashMap<>();

        tracks.forEach(track -> result.put(track, maps.getOrDefault(track.id(), List.of())));
        return result;
    }

    @MutationMapping
    public ScoreSyncDto scoreSyncSave(@Argument final UUID trackId, @Argument final UUID fileId, @Argument final ScoreSyncInput input,
                                      @Argument final @Nullable Double version) {
        return service.save(trackId, fileId, input, version == null ? null : version.longValue());
    }

    @MutationMapping
    public boolean scoreSyncDelete(@Argument final UUID trackId, @Argument final UUID fileId) {
        return service.delete(trackId, fileId);
    }

    @MutationMapping
    public ScoreSyncDto scoreAlign(@Argument final UUID trackId, @Argument final UUID fileId) {
        return service.align(trackId, fileId);
    }
}
