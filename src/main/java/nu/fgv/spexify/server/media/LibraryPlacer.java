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

package nu.fgv.spexify.server.media;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.Track;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts an album's files where they belong: published tracks, tagged and with their lyrics and cover beside them, in
 * Navidrome's music folder; unpublished and trashed ones outside it. Idempotent: a file is moved only when its place
 * changed and tagged only when its tags would change.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class LibraryPlacer {

    private final MediaStorage storage;
    private final TagWriter tagWriter;

    public LibraryPlacer(final MediaStorage storage, final TagWriter tagWriter) {
        this.storage = storage;
        this.tagWriter = tagWriter;
    }

    /**
     * Places every track of the album. Updates each track's location and tags hash, which the caller saves.
     *
     * @return whether anything in Navidrome's music folder changed, so that it needs to scan
     */
    public boolean place(final Album album) {
        boolean musicChanged = false;
        final String coverChecksum = coverChecksum(album);

        for (final Track track : album.getTracks()) {
            final Location current = track.getLocation();
            final Location desired = track.desiredLocation();

            try {
                if (current != desired) {
                    if (!Files.isRegularFile(storage.trackFile(track, current))) {
                        log.warn("The file of track {} is missing from {}", track.getUuid(), current.folder());
                        continue;
                    }
                    storage.move(track, current, desired);
                    track.setLocation(desired);
                    track.setTagsHash(null);
                    if (desired != Location.MUSIC) {
                        track.setNavidromeId(null);
                    }
                    musicChanged |= current == Location.MUSIC || desired == Location.MUSIC;
                }
                if (desired == Location.MUSIC) {
                    final String hash = TagWriter.hash(track, coverChecksum);

                    if (!hash.equals(track.getTagsHash())) {
                        tagWriter.write(track, storage.trackFile(track));
                        writeLyrics(track);
                        track.setTagsHash(hash);
                        musicChanged = true;
                    }
                }
            } catch (final IOException e) {
                log.error("Could not place track {} of album {}", track.getUuid(), album.getUuid(), e);
            }
        }
        for (final Location location : Location.values()) {
            try {
                storage.syncCover(album, location);
            } catch (final IOException e) {
                log.error("Could not place the cover of album {} in {}", album.getUuid(), location.folder(), e);
            }
        }
        return musicChanged;
    }

    private void writeLyrics(final Track track) throws IOException {
        final Path synced = storage.lyricsFile(track, Location.MUSIC, true);
        final Path plain = storage.lyricsFile(track, Location.MUSIC, false);

        Files.deleteIfExists(synced);
        Files.deleteIfExists(plain);
        if (StringUtils.hasText(track.getLyrics())) {
            Files.writeString(track.isLyricsSynced() ? synced : plain, track.getLyrics(), StandardCharsets.UTF_8);
        }
    }

    private String coverChecksum(final Album album) {
        if (album.getCoverExtension() == null) {
            return "none";
        }
        try {
            return AudioPreparer.checksum(storage.coverFile(album));
        } catch (final IOException e) {
            return "missing";
        }
    }
}
