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
import nu.fgv.spexify.server.util.StoredText;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ConflictException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The album media (LP, EP, CD, digital …): everyone reads them, admins add, rename, reorder and delete them. A medium
 * that albums use, trashed ones included, cannot be deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AlbumMediumService {

    static final int MAX_NAME = 100;

    private final AlbumMediumRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;

    public AlbumMediumService(final AlbumMediumRepository repository, final CallerService callerService,
                              final AuditService auditService) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    static AlbumMediumDto toDto(final AlbumMedium medium) {
        return new AlbumMediumDto(medium.getUuid(), medium.getNameSv(), medium.getNameEn(), medium.aliasList(), medium.getPosition());
    }

    private static String name(final @Nullable String value) {
        if (!StringUtils.hasText(value) || value.strip().length() > MAX_NAME) {
            throw new BadRequestException("error.albumMedium.invalid");
        }
        return value.strip();
    }

    @Transactional(readOnly = true)
    public List<AlbumMediumDto> media() {
        callerService.current().requireUser();
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(AlbumMediumService::toDto).toList();
    }

    /**
     * How many albums, trashed ones included, have each medium.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> albumCounts(final Collection<UUID> ids) {
        final Map<Long, UUID> uuids = repository.findAll().stream().collect(Collectors.toMap(AlbumMedium::getId, AlbumMedium::getUuid));
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Object[] row : repository.countAlbumsByMedium()) {
            counts.put(uuids.get((Long) row[0]), ((Number) row[1]).intValue());
        }
        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> counts.getOrDefault(id, 0)));
    }

    /**
     * The medium for an album, or none.
     */
    @Transactional(readOnly = true)
    public @Nullable AlbumMedium mediumOrNone(final @Nullable UUID id) {
        return id == null ? null : medium(id);
    }

    @Transactional
    public AlbumMediumDto create(final AlbumMediumInput input) {
        callerService.current().requireAdmin();

        final AlbumMedium medium = new AlbumMedium();

        medium.setNameSv(name(input.nameSv()));
        medium.setNameEn(name(input.nameEn()));
        medium.setAliases(aliases(input.aliases()));
        medium.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(AlbumMedium::getPosition).max().orElse(0) + 1);
        repository.save(medium);
        auditService.record("CREATE", "ALBUM_MEDIUM", medium.getUuid(),
                StoredText.of("audit.albumMedium.create", StoredText.of("audit.name", medium.getNameSv(), medium.getNameEn())));
        return toDto(medium);
    }

    @Transactional
    public AlbumMediumDto update(final UUID id, final AlbumMediumInput input) {
        callerService.current().requireAdmin();

        final AlbumMedium medium = medium(id);

        medium.setNameSv(name(input.nameSv()));
        medium.setNameEn(name(input.nameEn()));
        if (input.aliases() != null) {
            medium.setAliases(aliases(input.aliases()));
        }
        auditService.record("UPDATE", "ALBUM_MEDIUM", medium.getUuid(),
                StoredText.of("audit.albumMedium.update", StoredText.of("audit.name", medium.getNameSv(), medium.getNameEn())));
        return toDto(medium);
    }

    @Transactional
    public List<AlbumMediumDto> reorder(final List<UUID> ids) {
        final Caller caller = callerService.current();

        caller.requireAdmin();

        final List<AlbumMedium> media = repository.findAll();
        final Map<UUID, AlbumMedium> byId = media.stream().collect(Collectors.toMap(AlbumMedium::getUuid, Function.identity()));

        if (ids.size() != media.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.albumMedium.reorder");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "ALBUM_MEDIUM", null, StoredText.of("audit.albumMedium.reorder"));
        return ids.stream().map(byId::get).map(AlbumMediumService::toDto).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final AlbumMedium medium = medium(id);
        final long albums = repository.countAlbums(medium);

        if (albums > 0) {
            throw new ConflictException("error.albumMedium.inUse", albums);
        }
        repository.delete(medium);
        auditService.record("DELETE", "ALBUM_MEDIUM", medium.getUuid(),
                StoredText.of("audit.albumMedium.delete", StoredText.of("audit.name", medium.getNameSv(), medium.getNameEn())));
    }

    private AlbumMedium medium(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.albumMedium.notFound", id));
    }

    private static String aliases(final @Nullable List<String> aliases) {
        final String joined = aliases == null ? "" : aliases.stream().filter(org.springframework.util.StringUtils::hasText)
                .map(String::strip).distinct().collect(java.util.stream.Collectors.joining("\n"));

        if (joined.length() > 2000) {
            throw new BadRequestException("error.albumMedium.name");
        }
        return joined;
    }

    /**
     * The medium whose Swedish or English name or one of whose aliases is the text, ignoring case and punctuation; for the
     * import (Spexipedia's docs/content-import.md, section 3).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AlbumMedium> match(final @Nullable String text) {
        final String wanted = normalize(text);

        if (wanted.isEmpty()) {
            return java.util.Optional.empty();
        }
        return repository.findAllByOrderByPositionAscIdAsc().stream().filter(medium -> normalize(medium.getNameSv()).equals(wanted)
                        || normalize(medium.getNameEn()).equals(wanted) || medium.aliasList().stream().anyMatch(alias -> normalize(alias).equals(wanted)))
                .findFirst();
    }

    static String normalize(final @Nullable String text) {
        return text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /**
     * IMPORT. A new medium with the name in both languages.
     */
    @Transactional
    public AlbumMedium createForImport(final String name) {
        callerService.current().requireImport();

        final AlbumMedium medium = new AlbumMedium();

        medium.setNameSv(name(name));
        medium.setNameEn(medium.getNameSv());
        medium.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(AlbumMedium::getPosition).max().orElse(0) + 1);
        repository.save(medium);
        auditService.record("CREATE", "ALBUM_MEDIUM", medium.getUuid(),
                StoredText.of("audit.albumMedium.createForImport", StoredText.of("audit.name", medium.getNameSv(), medium.getNameEn())));
        return medium;
    }

    public record AlbumMediumDto(UUID id, String nameSv, String nameEn, List<String> aliases, int position) {
    }

    public record AlbumMediumInput(@Nullable String nameSv, @Nullable String nameEn, @Nullable List<String> aliases) {

        public AlbumMediumInput(final @Nullable String nameSv, final @Nullable String nameEn) {
            this(nameSv, nameEn, null);
        }
    }
}
