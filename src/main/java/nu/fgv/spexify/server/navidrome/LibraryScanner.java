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

package nu.fgv.spexify.server.navidrome;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.catalog.TrackRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks Navidrome to scan when the music folder changed, and afterwards records Navidrome's id for every published
 * track and album, so that the web can star tracks and add them to playlists.
 *
 * <p>Tracks are matched by the uuid in the file's path ({@code <albumUuid>/<trackUuid>.<ext>}), which Navidrome reports
 * with {@code ND_SUBSONIC_DEFAULTREPORTREALPATH=true}, or else by the MusicBrainz id written into the tags.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class LibraryScanner {

    private static final Pattern UUID_FILE = Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.[a-z0-9]+$");
    private static final int MAX_RETRIES = 3;
    private final NavidromeClient client;
    private final TrackRepository trackRepository;
    private final nu.fgv.spexify.server.media.MediaStorage storage;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private volatile boolean pending = true;
    private volatile boolean fullScan;
    private volatile int retries;
    private volatile boolean scanning;
    private volatile boolean available;
    private volatile @Nullable Instant lastScanAt;
    private volatile @Nullable String lastError;

    public LibraryScanner(final NavidromeClient client, final TrackRepository trackRepository,
                          final nu.fgv.spexify.server.media.MediaStorage storage,
                          final TransactionTemplate transactionTemplate, final Clock clock) {
        this.client = client;
        this.trackRepository = trackRepository;
        this.storage = storage;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    static @Nullable String trackUuid(final NavidromeClient.Song song) {
        final Matcher matcher = UUID_FILE.matcher(song.path());

        if (matcher.find()) {
            return matcher.group(1);
        }
        return song.musicBrainzId().isEmpty() ? null : song.musicBrainzId();
    }

    /**
     * Marks that Navidrome needs to scan. The scan starts within ten seconds, once for many changes.
     */
    public void requestScan() {
        pending = true;
        retries = 0;
    }

    public Status status() {
        return new Status(available, scanning, pending, lastScanAt, lastError);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            client.ping();
            available = true;
        } catch (final IOException e) {
            log.warn("Navidrome is not available yet: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 10, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void tick() {
        try {
            final NavidromeClient.ScanStatus status = client.scanStatus();

            available = true;
            if (status.scanning()) {
                scanning = true;
                return;
            }
            if (scanning) {
                scanning = false;
                lastScanAt = clock.instant();
                matchIds();
            }
            if (pending) {
                pending = false;
                scanning = true;
                client.startScan(fullScan);
                fullScan = false;
            }
            lastError = null;
        } catch (final IOException | RuntimeException e) {
            available = false;
            lastError = e.getMessage();
            log.debug("Navidrome scan tick failed", e);
        }
    }

    void matchIds() throws IOException {
        final List<NavidromeClient.Song> songs = client.songs();
        final Map<String, NavidromeClient.Song> byTrackUuid = new HashMap<>();

        for (final NavidromeClient.Song song : songs) {
            final String uuid = trackUuid(song);

            if (uuid != null) {
                byTrackUuid.put(uuid, song);
            }
        }
        final int[] missing = {0};

        transactionTemplate.executeWithoutResult(_ -> {
            for (final Track track : trackRepository.findByLocation(Location.MUSIC)) {
                final NavidromeClient.Song song = byTrackUuid.get(track.getUuid().toString());
                final Album album = track.getAlbum();

                track.setNavidromeId(song == null ? null : song.id());
                if (song == null && storage.exists(track)) {
                    missing[0]++;
                }
                if (song != null && !song.albumId().isEmpty()) {
                    album.setNavidromeId(song.albumId());
                }
            }
        });
        log.info("Matched {} of Navidrome's {} songs to tracks; {} published tracks are not in Navidrome yet",
                byTrackUuid.size(), songs.size(), missing[0]);
        if (missing[0] > 0 && retries < MAX_RETRIES) {
            // The scan started before the files were in place, or Navidrome skipped folders it thought unchanged
            retries++;
            fullScan = true;
            pending = true;
        }
    }

    public record Status(boolean available, boolean scanning, boolean pending, @Nullable Instant lastScanAt,
                         @Nullable String lastError) {
    }
}
