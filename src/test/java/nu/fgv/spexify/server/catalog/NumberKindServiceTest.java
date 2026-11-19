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

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.spex.NumberDto;
import nu.fgv.spexify.server.spex.NumberKindDto;
import nu.fgv.spexify.server.spex.SpexipediaClient;
import nu.fgv.spexify.server.user.CallerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spexipedia's number kinds of the listed tracks: which kinds have tracks, the tracks of a kind, a track's kind and the
 * nightly refresh.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class NumberKindServiceTest {

    private static final NumberKindDto OVERTURE = new NumberKindDto(uuid(21), "Ouvertyr", "Overture", 0, false);
    private static final NumberKindDto FINALE = new NumberKindDto(uuid(22), "Final", "Finale", 1, false);
    private static final NumberKindDto DUET = new NumberKindDto(uuid(23), "Duett", "Duet", 2, true);
    @Mock
    private SpexipediaClient client;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private CatalogService catalogService;
    @Mock
    private CallerService callerService;
    private NumberKindService service;

    @BeforeEach
    void setUp() {
        service = new NumberKindService(client, trackRepository, catalogService, callerService);
        lenient().when(callerService.current()).thenReturn(TestCallers.listener());
    }

    private static TrackDto trackDto(final UUID id, final UUID kindId) {
        return new TrackDto(id, uuid(1), "Album", 1, 1, "Titel", null, null, null, null, null, false, "flac", 1, 1000,
                null, true, true, null, null, false, null, kindId == null ? null : uuid(30), null, kindId, null, true, false, java.util.List.of());
    }

    @Test
    void onlyKindsWithListedTracksAreShownInSpexipediasOrder() {
        when(trackRepository.countListedByNumberKind()).thenReturn(List.of(new Object[]{uuid(22), 3L}, new Object[]{uuid(23), 1L}));
        when(client.numberKinds()).thenReturn(List.of(OVERTURE, FINALE, DUET));

        assertThat(service.kinds()).containsExactly(FINALE, DUET);
        assertThat(service.trackCounts()).isEqualTo(Map.of(uuid(22), 3, uuid(23), 1));
    }

    @Test
    void withoutSpexipediaThereAreNoKinds() {
        when(trackRepository.countListedByNumberKind()).thenReturn(List.<Object[]>of(new Object[]{uuid(22), 3L}));
        when(client.numberKinds()).thenThrow(new SpexipediaClient.SpexipediaCallException("down"));

        assertThat(service.kinds()).isEmpty();
    }

    @Test
    void kindsAreForListeners() {
        when(callerService.current()).thenReturn(TestCallers.noAccess());

        assertThatThrownBy(() -> service.kinds()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.tracks(uuid(21), 10)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theTracksOfAKindAreLimited() {
        final Track track = new Track();

        when(trackRepository.findListedByNumberKind(uuid(21), PageRequest.of(0, NumberKindService.MAX_TRACKS))).thenReturn(List.of(track));
        when(catalogService.toDto(track)).thenReturn(trackDto(uuid(5), uuid(21)));

        assertThat(service.tracks(uuid(21), 1000)).extracting(TrackDto::id).containsExactly(uuid(5));
    }

    @Test
    void aTrackHasItsNumbersKindWhenSpexipediaKnowsIt() {
        when(client.numberKinds()).thenReturn(List.of(OVERTURE, FINALE));

        assertThat(service.kindsOf(List.of(trackDto(uuid(5), uuid(21)), trackDto(uuid(6), null), trackDto(uuid(7), uuid(29)))))
                .isEqualTo(Map.of(uuid(5), OVERTURE));
    }

    @Test
    void tracksWithoutKindsDoNotAskSpexipedia() {
        assertThat(service.kindsOf(List.of(trackDto(uuid(6), null)))).isEmpty();
        verify(client, never()).numberKinds();

        when(client.numberKinds()).thenThrow(new SpexipediaClient.SpexipediaCallException("down"));
        assertThat(service.kindsOf(List.of(trackDto(uuid(5), uuid(21))))).isEmpty();
    }

    @Test
    void theNightlyRefreshCopiesTheKindsOfTheNumbers() {
        final Track linked = new Track();
        final Track gone = new Track();

        linked.setNumberId(uuid(30));
        linked.setNumberKindId(uuid(21));
        gone.setNumberId(uuid(31));
        gone.setNumberKindId(uuid(21));
        when(trackRepository.findByNumberIdIsNotNull()).thenReturn(List.of(linked, gone));
        when(client.numbersByIds(List.of(uuid(30), uuid(31)))).thenReturn(List.of(new NumberDto(uuid(30), uuid(1), 1, 1, "Final", uuid(22))));

        service.refresh();

        assertThat(linked.getNumberKindId()).isEqualTo(uuid(22));
        // A number Spexipedia no longer has keeps what it had
        assertThat(gone.getNumberKindId()).isEqualTo(uuid(21));
    }

    @Test
    void theRefreshDoesNothingWithoutLinkedTracksAndSurvivesSpexipediaBeingDown() {
        when(trackRepository.findByNumberIdIsNotNull()).thenReturn(List.of());
        service.refresh();
        verify(client, never()).numbersByIds(any());

        final Track linked = new Track();

        linked.setNumberId(uuid(30));
        when(trackRepository.findByNumberIdIsNotNull()).thenReturn(List.of(linked));
        when(client.numbersByIds(any())).thenThrow(new SpexipediaClient.SpexipediaCallException("down"));
        service.refresh();
        assertThat(linked.getNumberKindId()).isNull();
    }
}
