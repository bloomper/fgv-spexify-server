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


package nu.fgv.spexify.server.lyrics;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.config.CacheConfig;
import nu.fgv.spexify.server.media.Ffmpeg;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.spex.SpexipediaClient;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ConflictException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The lyrics editor's backend: the saved versions, saving and restoring them, the recording's waveform, and the names
 * that singer labels stand for.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class LyricsService {

    static final int MAX_POINTS = 4_000;
    static final int WAVEFORM_RATE = 2_000;
    static final int MAX_LYRICS = 200_000;

    private final LyricsRevisionRepository repository;
    private final TrackRepository trackRepository;
    private final CatalogService catalogService;
    private final CallerService callerService;
    private final SpexipediaClient spexipediaClient;
    private final MediaStorage storage;
    private final Ffmpeg ffmpeg;
    private final Cache waveforms;

    public LyricsService(final LyricsRevisionRepository repository, final TrackRepository trackRepository, final CatalogService catalogService,
                         final CallerService callerService, final SpexipediaClient spexipediaClient, final MediaStorage storage,
                         final Ffmpeg ffmpeg, final CacheManager cacheManager) {
        this.repository = repository;
        this.trackRepository = trackRepository;
        this.catalogService = catalogService;
        this.callerService = callerService;
        this.spexipediaClient = spexipediaClient;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.waveforms = Objects.requireNonNull(cacheManager.getCache(CacheConfig.WAVEFORMS));
    }

    /**
     * The saved versions of each track's lyrics, newest first; for editors only.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<RevisionDto>> revisions(final Collection<UUID> trackIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);
        final Map<UUID, List<RevisionDto>> result = new HashMap<>();

        trackIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (caller == null || !caller.isEditor() || trackIds.isEmpty()) {
            return result;
        }

        final List<Track> tracks = trackRepository.findByUuidIn(trackIds);
        final Map<Long, UUID> ids = new HashMap<>();

        tracks.forEach(track -> ids.put(track.getId(), track.getUuid()));
        repository.findByTrackInOrderByIdDesc(tracks).forEach(revision -> result.get(ids.get(revision.getTrack().getId())).add(toDto(revision)));
        return result;
    }

    /**
     * EDITOR. Saves the lyrics, unless someone saved them after the version the editor started from.
     */
    @Transactional
    public TrackDto update(final UUID trackId, final @Nullable String lyrics, final boolean synced, final @Nullable Long baseRevisionId) {
        callerService.current().requireEditor();
        if (lyrics != null && lyrics.length() > MAX_LYRICS) {
            throw new BadRequestException("error.lyrics.tooLong");
        }

        final Track track = catalogService.editableTrack(trackId);
        final Long latest = repository.findFirstByTrackOrderByIdDesc(track).map(LyricsRevision::getId).orElse(null);

        if (!Objects.equals(latest, baseRevisionId)) {
            throw new ConflictException("error.lyrics.changed");
        }
        return catalogService.trackSetLyrics(trackId, lyrics, synced);
    }

    /**
     * EDITOR. Restores a saved version, as a new version.
     */
    @Transactional
    public TrackDto restore(final UUID trackId, final Long revisionId) {
        callerService.current().requireEditor();

        final Track track = catalogService.editableTrack(trackId);
        final LyricsRevision revision = repository.findById(revisionId)
                .filter(found -> found.getTrack().getId().equals(track.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("error.lyrics.revisionNotFound", revisionId));

        return catalogService.trackSetLyrics(trackId, revision.getLyrics(), revision.isSynced());
    }

    /**
     * EDITOR. The loudest sample in each of {@code points} slices of the recording, from 0 to 1, cached by the file.
     */
    public List<Double> waveform(final UUID trackId, final int points) {
        callerService.current().requireEditor();

        final int n = Math.clamp(points, 1, MAX_POINTS);
        final Track track = catalogService.playableTrack(trackId);
        final Path file = storage.trackFile(track);
        final String key = track.getUuid() + ":" + track.getFileChecksum() + ":" + n;
        final Cache.ValueWrapper cached = waveforms.get(key);

        if (cached != null && cached.get() instanceof List<?> list) {
            return list.stream().map(Double.class::cast).toList();
        }
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("error.track.fileMissing", trackId);
        }
        try {
            final List<Double> peaks = peaks(ffmpeg.decode(file, WAVEFORM_RATE, (int) Math.min(Integer.MAX_VALUE, track.getDurationMs() / 1000 + 5)), n);

            waveforms.put(key, peaks);
            return peaks;
        } catch (final IOException e) {
            log.warn("Could not decode {} for its waveform: {}", trackId, e.getMessage());
            throw new ResourceNotFoundException("error.track.fileMissing", trackId);
        }
    }

    static List<Double> peaks(final float[] samples, final int points) {
        final List<Double> peaks = new ArrayList<>(points);
        double max = 1e-9;
        final double[] raw = new double[points];

        for (int p = 0; p < points; p++) {
            final int from = (int) ((long) samples.length * p / points);
            final int to = (int) ((long) samples.length * (p + 1) / points);
            double peak = 0;

            for (int i = from; i < to; i++) {
                peak = Math.max(peak, Math.abs(samples[i]));
            }
            raw[p] = peak;
            max = Math.max(max, peak);
        }
        for (final double peak : raw) {
            peaks.add(Math.round(peak / max * 1000) / 1000.0);
        }
        return peaks;
    }

    /**
     * The names each track's singer labels stand for, by the characters of its album's spex in Spexipedia. Labels are
     * resolved per spex family; a track whose album has no spex, or when Spexipedia is unavailable, gets none.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<SingerDto>> singers(final Map<UUID, @Nullable String> lyricsByTrack) {
        final Map<UUID, List<SingerDto>> result = new HashMap<>();
        final Map<UUID, @Nullable String> withLabels = new HashMap<>();

        lyricsByTrack.forEach((id, lyrics) -> {
            result.put(id, List.of());
            if (!SingerLabels.labels(lyrics).isEmpty()) {
                withLabels.put(id, lyrics);
            }
        });
        if (withLabels.isEmpty()) {
            return result;
        }
        for (final Track track : trackRepository.findByUuidIn(withLabels.keySet())) {
            final Set<UUID> spex = new LinkedHashSet<>();

            track.getAlbum().getSpex().forEach(ref -> spex.add(ref.getId()));

            final List<String> characters = new ArrayList<>();

            try {
                for (final UUID id : spex) {
                    characters.addAll(spexipediaClient.characters(id));
                }
            } catch (final RuntimeException e) {
                log.debug("No characters from Spexipedia for now: {}", e.getMessage());
                continue;
            }
            result.put(track.getUuid(), SingerLabels.resolve(withLabels.get(track.getUuid()), characters).entrySet().stream()
                    .map(entry -> new SingerDto(entry.getKey(), entry.getValue())).toList());
        }
        return result;
    }

    private static RevisionDto toDto(final LyricsRevision revision) {
        return new RevisionDto(revision.getId(), revision.getLyrics(), revision.isSynced(), revision.getCreatedAt(), revision.getCreatedByName());
    }

    public record RevisionDto(Long id, @Nullable String lyrics, boolean synced, Instant createdAt,
                              @Nullable String createdBy) {
    }

    public record SingerDto(String label, String name) {
    }
}
