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
import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.catalog.AlbumKindService.AlbumKindDto;
import nu.fgv.spexify.server.catalog.AlbumKindService.AlbumKindInput;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admins configure the album kinds; kinds in use and the last kind stay.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class AlbumKindServiceTest {

    private final List<AlbumKind> kinds = new ArrayList<>();
    @Mock
    private AlbumKindRepository repository;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private AlbumKindService service;

    @BeforeEach
    void setUp() {
        service = new AlbumKindService(repository, callerService, auditService);
        kinds.add(CatalogServiceTest.kind(1, "Föreställning", "Show"));
        kinds.add(CatalogServiceTest.kind(2, "Studio", "Studio"));
        lenient().when(repository.findAll()).thenReturn(kinds);
        lenient().when(repository.findAllByOrderByPositionAscIdAsc()).thenReturn(kinds);
        lenient().when(repository.findFirstByOrderByPositionAscIdAsc()).thenReturn(Optional.of(kinds.getFirst()));
        lenient().when(repository.findByUuid(uuid(101))).thenReturn(Optional.of(kinds.get(0)));
        lenient().when(repository.findByUuid(uuid(102))).thenReturn(Optional.of(kinds.get(1)));
        lenient().when(repository.count()).thenAnswer(_ -> (long) kinds.size());
    }

    @Test
    void listenersReadTheKindsInOrderButCannotChangeThem() {
        when(callerService.current()).thenReturn(TestCallers.listener());

        assertThat(service.kinds()).extracting(AlbumKindDto::nameEn).containsExactly("Show", "Studio");
        assertThatThrownBy(() -> service.create(new AlbumKindInput("Trailer", "Trailer"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(uuid(102))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aNewKindIsAddedLastWithTrimmedNames() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        final AlbumKindDto created = service.create(new AlbumKindInput("  Jubileum ", " Jubilee "));

        assertThat(created.nameSv()).isEqualTo("Jubileum");
        assertThat(created.nameEn()).isEqualTo("Jubilee");
        assertThat(created.position()).isEqualTo(3);
        verify(auditService).record(eq("CREATE"), eq("ALBUM_KIND"), any(), eq("Skapade albumsorten Jubileum"));
    }

    @Test
    void bothNamesAreRequired() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThatThrownBy(() -> service.create(new AlbumKindInput("Jubileum", " "))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(uuid(101), new AlbumKindInput(null, "Show"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new AlbumKindInput("x".repeat(101), "x"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aKindInUseCannotBeDeleted() {
        when(callerService.current()).thenReturn(TestCallers.admin());
        when(repository.countAlbums(kinds.get(1))).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(uuid(102))).isInstanceOf(ConflictException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void anUnusedKindIsDeletedButNotTheLastOne() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        service.delete(uuid(102));

        verify(repository).delete(kinds.get(1));
        kinds.remove(1);
        assertThatThrownBy(() -> service.delete(uuid(101))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void reorderingTakesEveryKindOnceAndTheFirstBecomesTheDefault() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThatThrownBy(() -> service.reorder(List.of(uuid(102)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.reorder(List.of(uuid(102), uuid(102)))).isInstanceOf(BadRequestException.class);

        final List<AlbumKindDto> reordered = service.reorder(List.of(uuid(102), uuid(101)));

        assertThat(reordered).extracting(AlbumKindDto::position).containsExactly(1, 2);
        assertThat(kinds.get(1).getPosition()).isEqualTo(1);
        assertThat(kinds.get(0).getPosition()).isEqualTo(2);
    }

    @Test
    void withoutAKindAnAlbumGetsTheDefault() {
        assertThat(service.kindOrDefault(null)).isSameAs(kinds.getFirst());
        assertThat(service.defaults(List.of(uuid(101), uuid(102)))).containsEntry(uuid(101), true).containsEntry(uuid(102), false);
    }
}
