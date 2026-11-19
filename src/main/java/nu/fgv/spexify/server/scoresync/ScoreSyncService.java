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


package nu.fgv.spexify.server.scoresync;

import nu.fgv.spexify.server.audit.AuditService;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.ScoreSyncDto;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.ScoreSyncInput;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.SyncJumpDto;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.SyncPointDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileKind;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetMusicDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicService;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ConflictException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Score sync maps: listeners see each score's map that is done, the manual one before the automatic one; editors see
 * every map, also pending and failed ones, make maps by hand and ask for automatic ones.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ScoreSyncService {

    static final int MAX_POINTS = 5_000;
    static final int MAX_JUMPS = 500;

    private final ScoreSyncRepository repository;
    private final TrackRepository trackRepository;
    private final CatalogService catalogService;
    private final SheetMusicService sheetMusicService;
    private final CallerService callerService;
    private final AuditService auditService;
    private final ScoreAlignWorker worker;
    private final JsonMapper json;

    public ScoreSyncService(final ScoreSyncRepository repository, final TrackRepository trackRepository, final CatalogService catalogService,
                            final SheetMusicService sheetMusicService, final CallerService callerService, final AuditService auditService,
                            final ScoreAlignWorker worker, final JsonMapper json) {
        this.repository = repository;
        this.trackRepository = trackRepository;
        this.catalogService = catalogService;
        this.sheetMusicService = sheetMusicService;
        this.callerService = callerService;
        this.auditService = auditService;
        this.worker = worker;
        this.json = json;
    }

    /**
     * The maps of each track that the caller sees, by track id; nothing for callers without access to the archive.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ScoreSyncDto>> forTracks(final Collection<UUID> trackIds) {
        final Caller caller = callerService.currentIfAny().orElse(null);
        final Map<UUID, List<ScoreSyncDto>> result = new HashMap<>();

        trackIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (caller == null || !caller.isUser() || !caller.archiveUser() || trackIds.isEmpty()) {
            return result;
        }

        final List<Track> tracks = trackRepository.findByUuidIn(trackIds);
        final Map<Long, UUID> ids = new HashMap<>();

        tracks.forEach(track -> ids.put(track.getId(), track.getUuid()));
        for (final ScoreSync sync : repository.findByTrackIn(tracks).stream()
                .sorted(Comparator.comparing((ScoreSync sync) -> sync.getFileId()).thenComparing(sync -> sync.getMethod().ordinal()))
                .toList()) {
            final UUID trackId = ids.get(sync.getTrack().getId());
            final List<ScoreSyncDto> list = result.get(trackId);

            if (caller.isEditor()) {
                list.add(toDto(sync, trackId));
            } else if (sync.getStatus() == ScoreSync.Status.DONE && list.stream().noneMatch(found -> found.fileId().equals(sync.getFileId()))) {
                // Manual maps come first: the listener gets the one that wins
                list.add(toDto(sync, trackId));
            }
        }
        return result;
    }

    /**
     * EDITOR, with access to the archive. Saves a map made by hand, which wins over the automatic one.
     *
     * @param version the version of the map the editor started from, null for a new one
     */
    @Transactional
    public ScoreSyncDto save(final UUID trackId, final UUID fileId, final ScoreSyncInput input, final @Nullable Long version) {
        final Track track = editableTrack(trackId);
        final SheetFileDto file = score(track, fileId);

        validate(input);

        final ScoreSync sync = repository.findByTrackAndFileIdAndMethod(track, fileId, ScoreSync.Method.MANUAL).orElseGet(() -> {
            final ScoreSync created = new ScoreSync();

            created.setTrack(track);
            created.setFileId(fileId);
            created.setMethod(ScoreSync.Method.MANUAL);
            return created;
        });

        // Someone else saved a map since the editor opened it, or made one while they started a new one
        if (sync.getId() != null && (version == null || !Objects.equals(sync.getVersion(), version))) {
            throw new ConflictException("error.scoreSync.changed");
        }
        sync.setStatus(ScoreSync.Status.DONE);
        sync.setPoints(json.writeValueAsString(input.points().stream().map(point -> List.of(point.seconds(), point.measure(), point.beat())).toList()));
        sync.setJumps(input.jumps() == null || input.jumps().isEmpty() ? null : json.writeValueAsString(input.jumps()));
        sync.setQuality(1.0);
        sync.setError(null);
        sync.setScoreChecksum(file.checksum());
        sync.setAudioChecksum(track.getFileChecksum());

        final ScoreSync saved = repository.saveAndFlush(sync);

        auditService.record("UPDATE", "TRACK", track.getUuid(), "Synkade noterna till " + track.getTitle() + " för hand");
        return toDto(saved, track.getUuid());
    }

    /**
     * EDITOR. Removes the map made by hand, so that the automatic one, if any, is used again.
     */
    @Transactional
    public boolean delete(final UUID trackId, final UUID fileId) {
        final Track track = editableTrack(trackId);

        return repository.findByTrackAndFileIdAndMethod(track, fileId, ScoreSync.Method.MANUAL).map(sync -> {
            repository.delete(sync);
            auditService.record("UPDATE", "TRACK", track.getUuid(), "Tog bort den handgjorda notsynken till " + track.getTitle());
            return true;
        }).orElse(false);
    }

    /**
     * EDITOR, with access to the archive. Asks for the recording to be aligned with the score in the background,
     * replacing the earlier automatic map once done.
     */
    @Transactional
    public ScoreSyncDto align(final UUID trackId, final UUID fileId) {
        final Track track = editableTrack(trackId);
        final SheetFileDto file = score(track, fileId);
        final ScoreSync sync = queue(track, file);

        auditService.record("UPDATE", "TRACK", track.getUuid(), "Bad om att noterna till " + track.getTitle() + " synkas automatiskt");
        return toDto(sync, track.getUuid());
    }

    /**
     * Queues an automatic alignment of the track with the score, for the maintenance job too; no caller checks.
     */
    @Transactional
    public ScoreSync queue(final Track track, final SheetFileDto file) {
        final ScoreSync sync = repository.findByTrackAndFileIdAndMethod(track, file.id(), ScoreSync.Method.AUTOMATIC).orElseGet(() -> {
            final ScoreSync created = new ScoreSync();

            created.setTrack(track);
            created.setFileId(file.id());
            created.setMethod(ScoreSync.Method.AUTOMATIC);
            return created;
        });

        sync.setStatus(ScoreSync.Status.PENDING);
        sync.setError(null);

        final ScoreSync saved = repository.saveAndFlush(sync);

        worker.enqueueAfterCommit(saved.getId());
        return saved;
    }

    private Track editableTrack(final UUID trackId) {
        final Caller caller = callerService.current();

        caller.requireEditor();
        if (!caller.archiveUser()) {
            throw new AccessDeniedException("Access to Spexarkivet required");
        }
        return catalogService.playableTrack(trackId);
    }

    /**
     * A MusicXML file of the track's sheet music.
     */
    private SheetFileDto score(final Track track, final UUID fileId) {
        final SheetMusicDto sheetMusic = track.getNumberId() == null ? null : sheetMusicService.byNumbers(List.of(track.getNumberId())).get(track.getNumberId());
        final SheetFileDto file = sheetMusic == null ? null : sheetMusic.file(fileId);

        if (file == null) {
            throw new ResourceNotFoundException("error.sheetMusic.notFound", fileId);
        }
        if (file.kind() != SheetFileKind.MUSIC_XML) {
            throw new BadRequestException("error.scoreSync.notMusicXml");
        }
        return file;
    }

    static void validate(final ScoreSyncInput input) {
        if (input.points().isEmpty() || input.points().size() > MAX_POINTS || (input.jumps() != null && input.jumps().size() > MAX_JUMPS)) {
            throw new BadRequestException("error.scoreSync.invalid");
        }

        double last = -1;

        for (final SyncPointDto point : input.points()) {
            if (!Double.isFinite(point.seconds()) || point.seconds() <= last || point.measure() < 0 || point.measure() > 100_000
                    || !Double.isFinite(point.beat()) || point.beat() < 0 || point.beat() > 1_000) {
                throw new BadRequestException("error.scoreSync.invalid");
            }
            last = point.seconds();
        }
        if (input.jumps() != null) {
            for (final SyncJumpDto jump : input.jumps()) {
                if (jump.after() < 0 || jump.to() < 0 || jump.after() > 100_000 || jump.to() > 100_000 || (jump.nth() != null && jump.nth() < 0)) {
                    throw new BadRequestException("error.scoreSync.invalid");
                }
            }
        }
    }

    ScoreSyncDto toDto(final ScoreSync sync, final UUID trackId) {
        final List<SyncPointDto> points = sync.getPoints() == null ? List.of()
                : json.readValue(sync.getPoints(), new TypeReference<List<List<Double>>>() {
                }).stream()
                .filter(point -> point.size() >= 3)
                .map(point -> new SyncPointDto(point.get(0), point.get(1).intValue(), point.get(2)))
                .toList();
        final List<SyncJumpDto> jumps = sync.getJumps() == null ? List.of() : json.readValue(sync.getJumps(), new TypeReference<List<SyncJumpDto>>() {
        });
        final boolean stale = sync.getAudioChecksum() != null && !sync.getAudioChecksum().equals(sync.getTrack().getFileChecksum());

        return new ScoreSyncDto(trackId, sync.getFileId(), sync.getMethod(), sync.getStatus(), points, jumps, sync.getQuality(),
                sync.getError(), stale, sync.getLastModifiedAt() != null ? sync.getLastModifiedAt() : sync.getCreatedAt(),
                sync.getVersion());
    }

    /**
     * Whether an automatic map is still what the job would make: done or failed for this score and recording.
     */
    static boolean current(final ScoreSync sync, final SheetFileDto file, final Track track) {
        return sync.getStatus() != ScoreSync.Status.PENDING && sync.getStatus() != ScoreSync.Status.RUNNING
                ? Objects.equals(sync.getScoreChecksum(), file.checksum()) && Objects.equals(sync.getAudioChecksum(), track.getFileChecksum())
                : true;
    }
}
