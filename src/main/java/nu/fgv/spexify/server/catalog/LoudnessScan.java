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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.media.LibraryPlacer;
import nu.fgv.spexify.server.media.LoudnessMeter;
import nu.fgv.spexify.server.media.MediaStorage;
import nu.fgv.spexify.server.navidrome.LibraryScanner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Measures the loudness of the tracks that have none yet, e.g. those uploaded before Spexify measured it, and rewrites
 * the tags of their albums' tracks in the music folder with the ReplayGain values. One track per transaction, so that a
 * long run holds no locks and an interrupted one keeps what it measured.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class LoudnessScan {

    private final TrackRepository trackRepository;
    private final MediaStorage storage;
    private final LoudnessMeter meter;
    private final LibraryPlacer placer;
    private final LibraryScanner scanner;
    private final TransactionTemplate transactionTemplate;

    public LoudnessScan(final TrackRepository trackRepository, final MediaStorage storage, final LoudnessMeter meter,
                        final LibraryPlacer placer, final LibraryScanner scanner, final TransactionTemplate transactionTemplate) {
        this.trackRepository = trackRepository;
        this.storage = storage;
        this.meter = meter;
        this.placer = placer;
        this.scanner = scanner;
        this.transactionTemplate = transactionTemplate;
    }

    public void run() {
        final List<Long> ids = trackRepository.findByLoudnessLufsIsNullAndTrashedAtIsNull().stream().map(Track::getId).toList();
        int measured = 0;

        for (final Long id : ids) {
            if (Boolean.TRUE.equals(transactionTemplate.execute(_ -> measure(id)))) {
                measured++;
            }
        }
        log.info("Measured the loudness of {} of {} tracks", measured, ids.size());
    }

    private boolean measure(final Long id) {
        final Track track = trackRepository.findById(id).orElse(null);

        if (track == null || track.loudness() != null) {
            return false;
        }

        final Path file = storage.trackFile(track);

        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            final var loudness = meter.measure(file);

            if (loudness.isEmpty()) {
                return false;
            }
            track.setLoudness(loudness.get());
        } catch (final IOException e) {
            log.warn("Could not measure the loudness of track {}", track.getUuid(), e);
            return false;
        }
        if (placer.place(track.getAlbum())) {
            scanner.requestScan();
        }
        return true;
    }
}
