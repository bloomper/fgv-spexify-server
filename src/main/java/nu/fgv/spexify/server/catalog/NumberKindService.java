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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.spex.NumberDto;
import nu.fgv.spexify.server.spex.NumberKindDto;
import nu.fgv.spexify.server.spex.SpexipediaClient;
import nu.fgv.spexify.server.user.CallerService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spexipedia's number kinds (overture, finale, scene change…) of the numbers tracks are recordings of, for the start
 * page's shelf per kind and the kind's icon in track lists. The kinds are Spexipedia's data, never a list of
 * Spexify's own: a new kind there gives a new shelf here once a listed track is of it. Each track keeps its number's
 * kind, copied when it is linked and refreshed at night, since Spexipedia's change feed does not carry it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class NumberKindService {

    static final int MAX_TRACKS = 100;

    private final SpexipediaClient client;
    private final TrackRepository trackRepository;
    private final CatalogService catalogService;
    private final CallerService callerService;

    public NumberKindService(final SpexipediaClient client, final TrackRepository trackRepository,
                             final CatalogService catalogService, final CallerService callerService) {
        this.client = client;
        this.trackRepository = trackRepository;
        this.catalogService = catalogService;
        this.callerService = callerService;
    }

    /**
     * Spexipedia's number kinds by id; empty when Spexipedia is unavailable.
     */
    private Map<UUID, NumberKindDto> kindsById() {
        try {
            return client.numberKinds().stream().collect(Collectors.toMap(NumberKindDto::id, Function.identity(), (a, _) -> a));
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            log.debug("Could not read the number kinds from Spexipedia", e);
            return Map.of();
        }
    }

    /**
     * USER. The number kinds that listed tracks are recordings of, in Spexipedia's order; empty when Spexipedia is
     * unavailable.
     */
    @Transactional(readOnly = true)
    public List<NumberKindDto> kinds() {
        callerService.current().requireUser();

        final Map<UUID, Integer> counts = trackCounts();

        try {
            return client.numberKinds().stream().filter(kind -> counts.containsKey(kind.id())).toList();
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            log.debug("Could not read the number kinds from Spexipedia", e);
            return List.of();
        }
    }

    /**
     * How many listed tracks are of each kind; kinds without any are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> trackCounts() {
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Object[] row : trackRepository.countListedByNumberKind()) {
            counts.put(row[0] instanceof UUID id ? id : UUID.fromString(row[0].toString()), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * USER. Listed tracks of numbers of the kind, newest album first.
     */
    @Transactional(readOnly = true)
    public List<TrackDto> tracks(final UUID kindId, final int limit) {
        callerService.current().requireUser();
        return trackRepository.findListedByNumberKind(kindId, PageRequest.of(0, Math.clamp(limit, 1, MAX_TRACKS)))
                .stream().map(catalogService::toDto).toList();
    }

    /**
     * The kinds of the tracks, by track id; a track that is not linked, whose number has no kind or whose kind
     * Spexipedia does not know (or when it is unavailable) has none.
     */
    public Map<UUID, NumberKindDto> kindsOf(final Collection<TrackDto> tracks) {
        if (tracks.stream().allMatch(track -> track.numberKindId() == null)) {
            return Map.of();
        }

        final Map<UUID, NumberKindDto> kinds = kindsById();
        final Map<UUID, NumberKindDto> result = new HashMap<>();

        for (final TrackDto track : tracks) {
            final NumberKindDto kind = track.numberKindId() == null ? null : kinds.get(track.numberKindId());

            if (kind != null) {
                result.put(track.id(), kind);
            }
        }
        return result;
    }

    /**
     * Copies the current kind of every linked track's number from Spexipedia, at night; leaves them as they are when
     * Spexipedia is unavailable.
     */
    @Transactional
    public void refresh() {
        final List<Track> tracks = trackRepository.findByNumberIdIsNotNull();

        if (tracks.isEmpty()) {
            return;
        }
        try {
            final Map<UUID, NumberDto> numbers = client.numbersByIds(tracks.stream().map(Track::getNumberId)
                            .filter(Objects::nonNull).distinct().toList())
                    .stream().collect(Collectors.toMap(NumberDto::id, Function.identity(), (a, _) -> a));

            for (final Track track : tracks) {
                final NumberDto number = numbers.get(track.getNumberId());

                if (number != null) {
                    track.setNumberKindId(number.kindId());
                }
            }
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            log.warn("Could not refresh the number kinds from Spexipedia: {}", e.getMessage());
        }
    }
}
