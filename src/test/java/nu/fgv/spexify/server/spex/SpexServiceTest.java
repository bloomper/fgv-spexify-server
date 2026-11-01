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

package nu.fgv.spexify.server.spex;

import nu.fgv.spexify.server.TestCallers;
import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumRepository;
import nu.fgv.spexify.server.catalog.SpexRef;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.SpexregisterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spex read from Spexregister: searching, single spex, and copying them onto albums.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class SpexServiceTest {

    private static final SpexDto NOBEL = new SpexDto(1L, "Nobel", 1975, false, null, 1L, "Chalmersspexet");
    private static final SpexDto NOBEL_REVIVAL = new SpexDto(2L, "Nobel", 2026, true, 1L, 1L, "Chalmersspexet");
    private static final SpexDto CLEOPATRA = new SpexDto(3L, "Cleopatra", 2024, false, null, 1L, "Chalmersspexet");
    @Mock
    private SpexregisterClient client;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private CallerService callerService;
    private SpexService service;

    @BeforeEach
    void setUp() {
        service = new SpexService(client, albumRepository, callerService, new TestClock(Instant.parse("2026-11-01T10:00:00Z")));
        lenient().when(callerService.current()).thenReturn(TestCallers.editor());
    }

    @Test
    void searchMatchesTitleOrYearNewestFirst() {
        when(client.allSpex()).thenReturn(List.of(NOBEL, CLEOPATRA, NOBEL_REVIVAL));

        assertThat(service.search(" NOB ")).containsExactly(NOBEL_REVIVAL, NOBEL);
        assertThat(service.search("2024")).containsExactly(CLEOPATRA);
        assertThat(service.search("")).hasSize(3);
    }

    @Test
    void searchIsForEditorsAndFailsWhileSpexregisterIsDown() {
        when(callerService.current()).thenReturn(TestCallers.listener());
        assertThatThrownBy(() -> service.search("nob")).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(TestCallers.editor());
        when(client.allSpex()).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));
        assertThatThrownBy(() -> service.search("nob")).isInstanceOf(SpexregisterUnavailableException.class);
    }

    @Test
    void searchShowsAtMostAPage() {
        when(client.allSpex()).thenReturn(IntStream.range(0, 200)
                .mapToObj(i -> new SpexDto((long) i, "Spex " + i, 1900 + i, false, null, null, null)).toList());

        assertThat(service.search("spex")).hasSizeLessThan(200);
    }

    @Test
    void anUnknownSpexIsABadRequest() {
        when(client.spexById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byId(9L)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aSpexIsCopiedOntoAnAlbumWithTheTimeOfCopying() {
        final SpexRef ref = new SpexRef();

        service.copy(NOBEL_REVIVAL, ref);

        assertThat(ref.getId()).isEqualTo(2L);
        assertThat(ref.getTitle()).isEqualTo("Nobel");
        assertThat(ref.getYear()).isEqualTo(2026);
        assertThat(ref.getRevival()).isTrue();
        assertThat(ref.getCategoryName()).isEqualTo("Chalmersspexet");
        assertThat(ref.getRefreshedAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void theNightlyRefreshUpdatesEveryAlbumsCopyOfItsSpex() {
        final Album album = new Album();

        album.getSpex().setId(1L);
        album.getSpex().setTitle("Nobel (gammal titel)");
        when(albumRepository.findSpexIds()).thenReturn(List.of(1L, 99L));
        when(albumRepository.findBySpexIdIn(List.of(1L, 99L))).thenReturn(List.of(album));
        when(client.allSpex()).thenReturn(List.of(NOBEL));

        service.refresh();

        assertThat(album.getSpex().getTitle()).isEqualTo("Nobel");
    }

    @Test
    void theRefreshDoesNothingWithoutAlbumsAndSurvivesSpexregisterBeingDown() {
        when(albumRepository.findSpexIds()).thenReturn(List.of());
        service.refresh();
        verify(client, never()).allSpex();

        when(albumRepository.findSpexIds()).thenReturn(List.of(1L));
        when(client.allSpex()).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));
        service.refresh();
    }

    @Test
    void aPosterIsEmptyWhileSpexregisterIsDown() {
        when(client.poster(1L)).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));

        assertThat(service.poster(1L)).isEmpty();
    }
}
