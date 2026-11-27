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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.media.Ffmpeg;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetMusicDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicService;
import nu.fgv.spexify.server.util.StoredText;
import nu.fgv.spexify.server.util.TextException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Aligns recordings with scores in the background, one at a time, since an alignment takes a few seconds of CPU and a
 * few hundred megabytes. Maps left pending or running when the server stopped are taken up again after a start.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ScoreAlignWorker {

    private static final int MAX_ERROR = 1000;

    private final ScoreSyncRepository repository;
    private final SheetMusicService sheetMusicService;
    private final MediaStorage storage;
    private final Ffmpeg ffmpeg;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("score-align").daemon().factory());

    public ScoreAlignWorker(final ScoreSyncRepository repository, final SheetMusicService sheetMusicService, final MediaStorage storage,
                            final Ffmpeg ffmpeg, final JsonMapper json, final PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.sheetMusicService = sheetMusicService;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    void resume() {
        transaction.executeWithoutResult(_ -> repository.findByStatusIn(List.of(ScoreSync.Status.PENDING, ScoreSync.Status.RUNNING))
                .forEach(sync -> enqueue(sync.getId())));
    }

    /**
     * Aligns the map once the current transaction has committed, so that the worker sees it.
     */
    public void enqueueAfterCommit(final Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(id);
                }
            });
        } else {
            enqueue(id);
        }
    }

    private void enqueue(final Long id) {
        executor.execute(() -> {
            try {
                process(id);
            } catch (final RuntimeException e) {
                log.warn("Aligning score sync {} failed", id, e);
            }
        });
    }

    /**
     * Aligns one map in the calling thread.
     */
    void process(final Long id) {
        final Job job = transaction.execute(_ -> {
            final ScoreSync sync = repository.findById(id).orElse(null);

            if (sync == null || sync.getStatus() == ScoreSync.Status.DONE || sync.getStatus() == ScoreSync.Status.FAILED) {
                return null;
            }

            final Track track = sync.getTrack();

            sync.setStatus(ScoreSync.Status.RUNNING);
            return new Job(sync.getFileId(), track.getNumberId(), storage.trackFile(track), track.isLyricsSynced() ? track.getLyrics() : null,
                    track.getFileChecksum());
        });

        if (job == null) {
            return;
        }
        try {
            final SheetMusicDto sheetMusic = job.numberId() == null ? null : sheetMusicService.byNumbers(List.of(job.numberId())).get(job.numberId());
            final SheetFileDto file = sheetMusic == null ? null : sheetMusic.file(job.fileId());

            if (file == null) {
                throw new TextException(StoredText.of("scoreSync.failed.scoreGone"));
            }

            final MusicXml.Score score = MusicXml.parse(sheetMusicService.content(file));
            final float[] samples = ffmpeg.decode(job.audio(), ScoreAligner.SAMPLE_RATE, ScoreAligner.MAX_SECONDS);
            final long started = System.nanoTime();
            final ScoreAligner.Result result = ScoreAligner.align(score, samples, job.lyrics());

            log.info("Aligned score sync {} in {} ms: quality {}, {} points, {} lyric anchors", id, (System.nanoTime() - started) / 1_000_000,
                    result.quality(), result.points().size(), result.anchors());
            finish(id, sync -> {
                sync.setStatus(ScoreSync.Status.DONE);
                sync.setPoints(json.writeValueAsString(result.points().stream()
                        .map(point -> List.of(point.seconds(), point.measure(), point.beat())).toList()));
                sync.setQuality(result.quality());
                sync.setError(null);
                sync.setScoreChecksum(file.checksum());
                sync.setAudioChecksum(job.audioChecksum());
            });
        } catch (final Exception e) {
            log.info("Could not align score sync {}: {}", id, e.getMessage());

            final StoredText error = TextException.text(e);

            finish(id, sync -> {
                sync.setStatus(ScoreSync.Status.FAILED);
                sync.setError(error.encode(MAX_ERROR));
                sync.setAudioChecksum(job.audioChecksum());
            });
        }
    }

    private void finish(final Long id, final Consumer<ScoreSync> change) {
        transaction.executeWithoutResult(_ -> repository.findById(id).ifPresent(change));
    }

    private record Job(java.util.UUID fileId, java.util.@Nullable UUID numberId, Path audio, @Nullable String lyrics,
                       String audioChecksum) {
    }
}
