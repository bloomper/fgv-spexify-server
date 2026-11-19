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

import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

/**
 * Keeps every saved version of a track's lyrics. The first change also keeps what was there before it, so that an
 * upload's or an import's lyrics can be restored.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class LyricsHistory {

    private final LyricsRevisionRepository repository;
    private final CallerService callerService;
    private final Clock clock;

    public LyricsHistory(final LyricsRevisionRepository repository, final CallerService callerService, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.clock = clock;
    }

    /**
     * Records the track's current lyrics as a new version, when they differ from what they were.
     *
     * @param before       the lyrics before the change
     * @param beforeSynced whether they were synchronised
     */
    public void changed(final Track track, final @Nullable String before, final boolean beforeSynced) {
        if (Objects.equals(before, track.getLyrics()) && beforeSynced == track.isLyricsSynced()) {
            return;
        }
        if (!repository.existsByTrack(track) && before != null) {
            final LyricsRevision original = new LyricsRevision();

            original.setTrack(track);
            original.setLyrics(before);
            original.setSynced(beforeSynced);
            original.setCreatedBy("system");
            original.setCreatedAt(clock.instant().minusMillis(1));
            repository.save(original);
        }

        final Caller caller = callerService.currentIfAny().orElse(null);
        final LyricsRevision revision = new LyricsRevision();

        revision.setTrack(track);
        revision.setLyrics(track.getLyrics());
        revision.setSynced(track.isLyricsSynced());
        revision.setCreatedBy(caller == null ? "system" : caller.subject());
        revision.setCreatedByName(caller == null ? null : caller.displayName() == null ? caller.username() : caller.displayName());
        revision.setCreatedAt(clock.instant());
        repository.save(revision);
    }
}
