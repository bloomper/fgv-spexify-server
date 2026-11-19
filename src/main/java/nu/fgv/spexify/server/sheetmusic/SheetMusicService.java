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


package nu.fgv.spexify.server.sheetmusic;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.config.CacheConfig;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileKind;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetMusicDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetVersionDto;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The sheet music of tracks: the arrangement of the number a track is a recording of, from Spexarkivet, cached for a
 * few minutes by number. Shown only to users who also have access to the archive, as the archive's spec says.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class SheetMusicService {

    private static final Map<String, String> TONICS_SV = Map.ofEntries(Map.entry("C", "C"), Map.entry("C_SHARP", "Ciss"),
            Map.entry("D_FLAT", "Dess"), Map.entry("D", "D"), Map.entry("D_SHARP", "Diss"), Map.entry("E_FLAT", "Ess"),
            Map.entry("E", "E"), Map.entry("F", "F"), Map.entry("F_SHARP", "Fiss"), Map.entry("G_FLAT", "Gess"),
            Map.entry("G", "G"), Map.entry("G_SHARP", "Giss"), Map.entry("A_FLAT", "Ass"), Map.entry("A", "A"),
            Map.entry("A_SHARP", "Aiss"), Map.entry("B_FLAT", "B"), Map.entry("B", "H"));

    private final ArchiveClient client;
    private final CatalogService catalogService;
    private final CallerService callerService;
    private final Cache cache;
    private final Cache files;

    public SheetMusicService(final ArchiveClient client, final CatalogService catalogService, final CallerService callerService,
                             final CacheManager cacheManager) {
        this.client = client;
        this.catalogService = catalogService;
        this.callerService = callerService;
        this.cache = Objects.requireNonNull(cacheManager.getCache(CacheConfig.SHEET_MUSIC));
        this.files = Objects.requireNonNull(cacheManager.getCache(CacheConfig.SHEET_FILES));
    }

    /**
     * A key in Swedish, as in the archive: e.g. "Fiss-moll", "B-dur".
     */
    static @Nullable String key(final @Nullable String tonic, final @Nullable String mode) {
        if (tonic == null || mode == null) {
            return null;
        }

        final String name = TONICS_SV.getOrDefault(tonic, tonic);

        return "MINOR".equals(mode) ? name.toLowerCase(Locale.ROOT) + "-moll" : name + "-dur";
    }

    private SheetMusicDto toDto(final ArchiveClient.Arrangement arrangement) {
        final UUID id = UUID.fromString(arrangement.arrangementId());

        return new SheetMusicDto(id, client.arrangementUrl(id), arrangement.versions().stream()
                .map(version -> new SheetVersionDto(UUID.fromString(version.id()), version.label(), version.arranger(),
                        version.recommended(), key(version.keyTonic(), version.keyMode()), version.tempo(),
                        version.files().stream().map(SheetMusicService::toDto).toList()))
                .toList());
    }

    private static SheetFileDto toDto(final ArchiveClient.File file) {
        return new SheetFileDto(UUID.fromString(file.id()), file.title(), file.name(), file.extension(), file.contentType(),
                file.size(), file.checksum(), file.pages(), file.roleSv(), file.roleEn(), file.instrumentsSv(), file.instrumentsEn(),
                file.desk(), SheetFileKind.of(file.extension(), file.contentType()));
    }

    /**
     * The sheet music of each number that has some, without checking the caller, for the alignment job. Numbers the
     * archive could not be asked about are left out and not cached.
     */
    public Map<UUID, SheetMusicDto> byNumbers(final Collection<UUID> numberIds) {
        final Map<UUID, SheetMusicDto> result = new HashMap<>();
        final List<UUID> missing = new ArrayList<>();

        if (!client.enabled()) {
            return result;
        }
        for (final UUID id : numberIds.stream().distinct().toList()) {
            final Cache.ValueWrapper cached = cache.get(id);

            if (cached != null && cached.get() instanceof Optional<?> value) {
                value.ifPresent(found -> result.put(id, (SheetMusicDto) found));
            } else {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            try {
                final Map<UUID, SheetMusicDto> fetched = new HashMap<>();

                for (final ArchiveClient.Arrangement arrangement : client.sheetMusic(missing)) {
                    fetched.put(UUID.fromString(arrangement.numberId()), toDto(arrangement));
                }
                missing.forEach(id -> cache.put(id, Optional.ofNullable(fetched.get(id))));
                result.putAll(fetched);
            } catch (final ArchiveClient.ArchiveUnavailableException e) {
                log.debug("No sheet music for now: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * The sheet music of each track, for users with access to the archive; nothing for others.
     *
     * @param numberIds the tracks' numbers, by track id; tracks without one have none
     */
    public Map<UUID, SheetMusicDto> forTracks(final Map<UUID, @Nullable UUID> numberIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);

        if (caller == null || !caller.isUser() || !caller.archiveUser()) {
            return Map.of();
        }

        final Map<UUID, SheetMusicDto> byNumber = byNumbers(numberIds.values().stream().filter(Objects::nonNull).toList());
        final Map<UUID, SheetMusicDto> result = new HashMap<>();

        numberIds.forEach((trackId, numberId) -> {
            if (numberId != null && byNumber.containsKey(numberId)) {
                result.put(trackId, byNumber.get(numberId));
            }
        });
        return result;
    }

    /**
     * A file of a track's sheet music, which the caller may play: its description and content.
     */
    public SheetFile file(final UUID trackId, final UUID fileId) {
        final Caller caller = callerService.current();

        if (!caller.archiveUser()) {
            throw new AccessDeniedException("Access to Spexarkivet required");
        }

        final Track track = catalogService.playableTrack(trackId);
        final SheetFileDto file = track.getNumberId() == null ? null
                : Optional.ofNullable(byNumbers(List.of(track.getNumberId())).get(track.getNumberId())).map(sheet -> sheet.file(fileId)).orElse(null);

        if (file == null) {
            throw new ResourceNotFoundException("error.sheetMusic.notFound", fileId);
        }
        return new SheetFile(file, content(file));
    }

    /**
     * A file's content, cached by its id and checksum, without checking the caller.
     *
     * @throws ResourceNotFoundException when the archive does not hand it out
     */
    public byte[] content(final SheetFileDto file) {
        final String key = file.id() + ":" + file.checksum();
        final Cache.ValueWrapper cached = files.get(key);

        if (cached != null && cached.get() instanceof byte[] content) {
            return content;
        }
        try {
            final byte[] content = client.file(file.id());

            files.put(key, content);
            return content;
        } catch (final ArchiveClient.ArchiveUnavailableException e) {
            log.info("Could not fetch sheet music {} from Spexarkivet: {}", file.id(), e.getMessage());
            throw new ResourceNotFoundException("error.sheetMusic.unavailable", file.id());
        }
    }

    public record SheetFile(SheetFileDto description, byte[] content) {
    }
}
