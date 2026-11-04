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
import nu.fgv.spexify.server.catalog.CatalogDtos.CreditDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Credits and radio.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class BrowseServiceTest {

    @Mock
    private TrackRepository trackRepository;
    @Mock
    private CatalogService catalogService;
    @Mock
    private CallerService callerService;
    private BrowseService service;

    private static Track track(final int id, final String title, final UUID spex, final String performers, final String melody) {
        final Album album = new Album();
        final Track track = new Track();

        album.setUuid(uuid(1000 + id));
        if (spex != null) {
            final SpexRef ref = new SpexRef();

            ref.setId(spex);
            album.getSpex().add(ref);
        }
        track.setId((long) id);
        track.setUuid(uuid(id));
        track.setAlbum(album);
        track.setTitle(title);
        track.setPerformers(performers);
        track.setMelody(melody);
        return track;
    }

    @BeforeEach
    void setUp() {
        service = new BrowseService(trackRepository, catalogService, callerService, new Random(1));
        lenient().when(callerService.current()).thenReturn(TestCallers.listener());
        lenient().when(catalogService.toDto(any())).thenAnswer(invocation -> {
            final Track track = invocation.getArgument(0);
            final TrackDto dto = mock(TrackDto.class);

            lenient().when(dto.title()).thenReturn(track.getTitle());
            return dto;
        });
    }

    @Test
    void namesAreSplitOnSeparatorsAndTheOriginalArtistComesFromTheParentheses() {
        assertThat(BrowseService.split("Kungen, Drottningen; Hjälten & Skurken / kungen")).containsExactly("Kungen", "Drottningen", "Hjälten", "Skurken");
        assertThat(BrowseService.split("Skurken och hans hantlangare")).containsExactly("Skurken och hans hantlangare");
        assertThat(BrowseService.originalArtist("Dancing Queen (ABBA)")).containsExactly("ABBA");
        assertThat(BrowseService.originalArtist("Sång (från förr) (Benny & Björn)")).containsExactly("Benny", "Björn");
        assertThat(BrowseService.originalArtist("Ouvertyr")).isEmpty();
    }

    @Test
    void creditsAreCountedWithoutRegardToCaseAndSortedTheSwedishWay() {
        when(trackRepository.findListed()).thenReturn(List.of(
                track(1, "A", uuid(90), "Örjan, Kören", "Dancing Queen (ABBA)"),
                track(2, "B", uuid(90), "kören", "Waterloo (ABBA)"),
                track(3, "C", uuid(91), "Anna", null)));

        assertThat(service.credits(CreditKind.PERFORMER)).containsExactly(
                new CreditDto("Anna", 1), new CreditDto("Kören", 2), new CreditDto("Örjan", 1));
        assertThat(service.credits(CreditKind.ORIGINAL_ARTIST)).containsExactly(new CreditDto("ABBA", 2));
        assertThat(service.creditTracks(CreditKind.PERFORMER, "KÖREN")).extracting(TrackDto::title).containsExactly("A", "B");
    }

    @Test
    void radioFromATrackStartsWithItAndPrefersTheSameMelodyAndPerformers() {
        final Track seed = track(1, "Seed", uuid(90), "Kungen", "Dancing Queen (ABBA)");
        final Track sameMelody = track(2, "Same melody", uuid(91), "Hjälten", "Dancing Queen (ABBA)");
        final Track samePerformer = track(3, "Same performer", uuid(92), "Kungen", null);
        final Track unrelated = track(4, "Unrelated", uuid(93), "Någon", null);

        when(trackRepository.findListed()).thenReturn(List.of(unrelated, samePerformer, sameMelody, seed));

        assertThat(service.radio(uuid(1), null, 10)).extracting(TrackDto::title)
                .containsExactly("Seed", "Same melody", "Same performer", "Unrelated");
        assertThat(service.radio(uuid(1), null, 2)).hasSize(2);
    }

    @Test
    void radioFromAnAlbumLeavesTheAlbumOutAndRadioNeedsAtMostOneSeed() {
        final Track seed = track(1, "Seed", uuid(90), "Kungen", null);
        final Track other = track(2, "Other", uuid(91), "Kungen", null);

        when(trackRepository.findListed()).thenReturn(List.of(seed, other));

        assertThat(service.radio(null, seed.getAlbum().getUuid(), 10)).extracting(TrackDto::title).containsExactly("Other");
        assertThat(service.radio(null, null, 10)).hasSize(2);
        assertThatThrownBy(() -> service.radio(uuid(1), uuid(1001), 10)).isInstanceOf(BadRequestException.class);
    }
}
