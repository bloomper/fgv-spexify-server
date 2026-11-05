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
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * Album collections.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    @Mock
    private AlbumCollectionRepository repository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private CatalogService catalogService;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private CollectionService service;

    private static Album album(final long id, final boolean listed) {
        final Album album = new Album();

        album.setId(id);
        album.setUuid(uuid(id));
        album.setTitle("Album " + id);
        album.setPublished(listed);
        return album;
    }

    @BeforeEach
    void setUp() {
        service = new CollectionService(repository, albumRepository, catalogService, callerService, auditService);
        lenient().when(callerService.current()).thenReturn(TestCallers.listener());
    }

    @Test
    void listenersSeeOnlyListedAlbumsAndNotCollectionsWithoutThem() {
        final Album listed = album(1, true);
        final Album hidden = album(2, false);
        final AlbumCollection some = new AlbumCollection();
        final AlbumCollection none = new AlbumCollection();

        some.setTitle("Alla år");
        some.getAlbums().addAll(List.of(hidden, listed));
        none.setTitle("Tom");
        none.getAlbums().add(hidden);
        when(repository.findAllByOrderByTitleAsc()).thenReturn(List.of(some, none));
        when(catalogService.toDto(any(), any())).thenAnswer(invocation -> {
            final Album album = invocation.getArgument(0);

            return new CatalogDtos.AlbumDto(album.getUuid(), album.getTitle(), null, null, null, List.of(), false,
                    album.isPublished(), null, null, null, 0, 0, Instant.EPOCH, null);
        });

        assertThat(service.collections()).singleElement().satisfies(collection -> {
            assertThat(collection.title()).isEqualTo("Alla år");
            assertThat(collection.albums()).extracting(CatalogDtos.AlbumDto::id).containsExactly(listed.getUuid());
        });
    }

    @Test
    void collectionsOfAnAlbumAreTheOnesItIsVisibleIn() {
        final Album listed = album(1, true);
        final AlbumCollection collection = new AlbumCollection();

        collection.setTitle("Alla år");
        collection.getAlbums().add(listed);
        when(repository.findContaining(List.of(listed.getUuid(), uuid(9)))).thenReturn(List.of(collection));

        assertThat(service.collectionsOf(List.of(listed.getUuid(), uuid(9)))).satisfies(refs -> {
            assertThat(refs.get(listed.getUuid())).extracting(CollectionService.CollectionRefDto::title).containsExactly("Alla år");
            assertThat(refs.get(uuid(9))).isEmpty();
        });
    }

    @Test
    void listenersCannotChangeCollectionsAndEditorsCannotDeleteThem() {
        final var input = new CollectionService.CollectionInput("Alla år", null, List.of());

        assertThatThrownBy(() -> service.create(input)).isInstanceOf(AccessDeniedException.class);
        when(callerService.current()).thenReturn(TestCallers.editor());
        assertThatThrownBy(() -> service.delete(uuid(1))).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void aCollectionNeedsATitleAndAlbumsOutsideTheTrash() {
        final Album trashed = album(1, true);

        trashed.setTrashedAt(Instant.now());
        when(callerService.current()).thenReturn(TestCallers.editor());
        when(albumRepository.findByUuidIn(any())).thenReturn(List.of(trashed));

        assertThatThrownBy(() -> service.create(new CollectionService.CollectionInput(" ", null, List.of())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new CollectionService.CollectionInput("Alla år", null, List.of(trashed.getUuid()))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void editorsKeepTheAlbumsInTheGivenOrder() {
        final Album first = album(1, true);
        final Album second = album(2, true);
        final AlbumCollection collection = new AlbumCollection();

        when(callerService.current()).thenReturn(TestCallers.editor());
        when(repository.findByUuid(uuid(50))).thenReturn(Optional.of(collection));
        when(albumRepository.findByUuidIn(any())).thenReturn(List.of(first, second));

        service.update(uuid(50), new CollectionService.CollectionInput(" Alla år ", " ", List.of(second.getUuid(), first.getUuid())));

        assertThat(collection.getTitle()).isEqualTo("Alla år");
        assertThat(collection.getDescription()).isNull();
        assertThat(collection.getAlbums()).containsExactly(second, first);
        verify(auditService).record(any(), any(), any(), any());
    }
}
