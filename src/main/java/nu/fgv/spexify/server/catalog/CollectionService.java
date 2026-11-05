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

import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Collections of albums, like Spexflix's collections of videos.
 *
 * <p>Listeners see a collection when at least one of its albums is listed, and only its listed albums. EDITOR creates
 * and changes collections; removing one is a permanent delete and therefore ADMIN's. The albums stay.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class CollectionService {

    private static final int MAX_TITLE = 255;
    private static final int MAX_DESCRIPTION = 4000;

    private final AlbumCollectionRepository repository;
    private final AlbumRepository albumRepository;
    private final CatalogService catalogService;
    private final CallerService callerService;
    private final AuditService auditService;

    public CollectionService(final AlbumCollectionRepository repository, final AlbumRepository albumRepository,
                             final CatalogService catalogService, final CallerService callerService,
                             final AuditService auditService) {
        this.repository = repository;
        this.albumRepository = albumRepository;
        this.catalogService = catalogService;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    private static boolean visible(final Album album, final Caller caller) {
        return caller.isEditor() ? !album.isTrashed() : album.isListed();
    }

    private static @Nullable String blankToNull(final @Nullable String value, final int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        final String stripped = value.strip();

        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }

    private CollectionDto toDto(final AlbumCollection collection, final Caller caller) {
        return new CollectionDto(collection.getUuid(), collection.getTitle(), collection.getDescription(),
                collection.getAlbums().stream().filter(album -> visible(album, caller))
                        .map(album -> catalogService.toDto(album, caller)).toList(),
                collection.getCreatedAt(), collection.getLastModifiedAt());
    }

    /**
     * Listeners get the collections with a listed album; EDITOR gets every collection.
     */
    @Transactional(readOnly = true)
    public List<CollectionDto> collections() {
        final Caller caller = callerService.current();

        caller.requireUser();
        return repository.findAllByOrderByTitleAsc().stream()
                .map(collection -> toDto(collection, caller))
                .filter(collection -> caller.isEditor() || !collection.albums().isEmpty())
                .toList();
    }

    @Transactional(readOnly = true)
    public @Nullable CollectionDto collection(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return repository.findByUuid(id)
                .map(collection -> toDto(collection, caller))
                .filter(collection -> caller.isEditor() || !collection.albums().isEmpty())
                .orElse(null);
    }

    /**
     * The collections each album is in, by title, for the caller.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<CollectionRefDto>> collectionsOf(final Collection<UUID> albumIds) {
        final Caller caller = callerService.current();
        final Map<UUID, List<CollectionRefDto>> result = new LinkedHashMap<>();

        caller.requireUser();
        albumIds.forEach(id -> result.put(id, new ArrayList<>()));
        for (final AlbumCollection collection : repository.findContaining(albumIds)) {
            final CollectionRefDto ref = new CollectionRefDto(collection.getUuid(), collection.getTitle());

            collection.getAlbums().stream()
                    .filter(album -> visible(album, caller))
                    .map(Album::getUuid)
                    .filter(result::containsKey)
                    .forEach(id -> result.get(id).add(ref));
        }
        return result;
    }

    @Transactional
    public CollectionDto create(final CollectionInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final AlbumCollection collection = new AlbumCollection();

        apply(collection, input);
        repository.save(collection);
        auditService.record("CREATE", "COLLECTION", collection.getUuid(), "Skapade samlingen " + collection.getTitle());
        return toDto(collection, caller);
    }

    @Transactional
    public CollectionDto update(final UUID id, final CollectionInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final AlbumCollection collection = find(id);

        apply(collection, input);
        auditService.record("UPDATE", "COLLECTION", collection.getUuid(), "Ändrade samlingen " + collection.getTitle());
        return toDto(collection, caller);
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final AlbumCollection collection = find(id);

        repository.delete(collection);
        auditService.record("DELETE", "COLLECTION", collection.getUuid(), "Tog bort samlingen " + collection.getTitle());
    }

    private void apply(final AlbumCollection collection, final CollectionInput input) {
        final String title = blankToNull(input.title(), MAX_TITLE);

        if (title == null) {
            throw new BadRequestException("error.collection.invalid");
        }

        final Set<UUID> ids = new LinkedHashSet<>(Objects.requireNonNullElse(input.albumIds(), List.of()));
        final Map<UUID, Album> albums = albumRepository.findByUuidIn(ids).stream()
                .collect(Collectors.toMap(Album::getUuid, Function.identity()));

        if (albums.size() != ids.size() || albums.values().stream().anyMatch(Album::isTrashed)) {
            throw new BadRequestException("error.collection.albums");
        }
        collection.setTitle(title);
        collection.setDescription(blankToNull(input.description(), MAX_DESCRIPTION));
        collection.getAlbums().clear();
        ids.forEach(albumId -> collection.getAlbums().add(albums.get(albumId)));
    }

    private AlbumCollection find(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.collection.notFound", id));
    }

    public record CollectionDto(UUID id, String title, @Nullable String description, List<AlbumDto> albums,
                                Instant createdAt, @Nullable Instant lastModifiedAt) {
    }

    public record CollectionRefDto(UUID id, String title) {
    }

    public record CollectionInput(String title, @Nullable String description, @Nullable List<UUID> albumIds) {
    }
}
