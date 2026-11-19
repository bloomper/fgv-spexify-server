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
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetFileKind;
import nu.fgv.spexify.server.sheetmusic.SheetMusicDtos.SheetMusicDto;
import nu.fgv.spexify.server.sheetmusic.SheetMusicService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The SCORE_ALIGN maintenance job: queues an automatic alignment for every listed track whose number has MusicXML in
 * its recommended arrangement version and no automatic map for that score and recording yet.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ScoreAlignJob {

    private final TrackRepository trackRepository;
    private final ScoreSyncRepository repository;
    private final SheetMusicService sheetMusicService;
    private final ScoreSyncService service;
    private final TransactionTemplate transaction;

    public ScoreAlignJob(final TrackRepository trackRepository, final ScoreSyncRepository repository, final SheetMusicService sheetMusicService,
                         final ScoreSyncService service, final PlatformTransactionManager transactionManager) {
        this.trackRepository = trackRepository;
        this.repository = repository;
        this.sheetMusicService = sheetMusicService;
        this.service = service;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void run() {
        final Integer queued = transaction.execute(_ -> {
            final List<Track> tracks = trackRepository.findListed().stream().filter(track -> track.getNumberId() != null).toList();
            final Map<UUID, SheetMusicDto> sheetMusic = sheetMusicService.byNumbers(tracks.stream().map(Track::getNumberId).filter(Objects::nonNull).toList());
            int count = 0;

            for (final Track track : tracks) {
                final SheetMusicDto music = sheetMusic.get(track.getNumberId());

                if (music == null || music.versions().isEmpty()) {
                    continue;
                }
                for (final SheetFileDto file : music.versions().getFirst().files()) {
                    if (file.kind() != SheetFileKind.MUSIC_XML) {
                        continue;
                    }

                    final boolean done = repository.findByTrackAndFileIdAndMethod(track, file.id(), ScoreSync.Method.AUTOMATIC)
                            .map(sync -> ScoreSyncService.current(sync, file, track)).orElse(false);

                    if (!done) {
                        service.queue(track, file);
                        count++;
                    }
                }
            }
            return count;
        });

        log.info("Queued {} score alignments", queued);
    }
}
