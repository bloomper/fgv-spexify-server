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

import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.config.SpexifyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The media folder: covers beside the tracks, deleting albums and wiping for the sample data.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class MediaStorageTest {

    @TempDir
    private Path root;
    private MediaStorage storage;
    private Album album;
    private Track track;

    @BeforeEach
    void setUp() throws Exception {
        storage = new MediaStorage(new SpexifyProperties(null, null, null, new SpexifyProperties.Media(root, DataSize.ofMegabytes(1), null, null, null),
                null, null, null, null, null));
        album = new Album();
        album.setCoverExtension("png");
        track = new Track();
        track.setAlbum(album);
        track.setFileExtension("flac");
        track.setLocation(Location.MUSIC);
        Files.write(storage.coverFile(album), new byte[]{9});
    }

    @Test
    void theCoverIsCopiedBesideTheTracksAndRemovedWhenNoneAreLeft() throws Exception {
        Files.createDirectories(storage.trackFile(track).getParent());
        Files.write(storage.trackFile(track), new byte[]{1});

        storage.syncCover(album, Location.MUSIC);
        assertThat(storage.albumFolder(album, Location.MUSIC).resolve("cover.png")).hasBinaryContent(new byte[]{9});

        Files.delete(storage.trackFile(track));
        storage.syncCover(album, Location.MUSIC);
        assertThat(storage.albumFolder(album, Location.MUSIC)).doesNotExist();
        storage.syncCover(album, Location.TRASH);
    }

    @Test
    void movingATrackTakesItsLyricsAway() throws Exception {
        Files.createDirectories(storage.trackFile(track).getParent());
        Files.write(storage.trackFile(track), new byte[]{1});
        Files.writeString(storage.lyricsFile(track, Location.MUSIC, true), "[00:01.00]Hej");

        storage.move(track, Location.MUSIC, Location.TRASH);

        assertThat(storage.trackFile(track, Location.TRASH)).exists();
        assertThat(storage.lyricsFile(track, Location.MUSIC, true)).doesNotExist();
    }

    @Test
    void deletingAnAlbumRemovesItsFoldersAndCover() throws Exception {
        Files.createDirectories(storage.trackFile(track).getParent());
        Files.write(storage.trackFile(track), new byte[]{1});

        storage.deleteAlbum(album);

        assertThat(storage.albumFolder(album, Location.MUSIC)).doesNotExist();
        assertThat(storage.coverFile(album)).doesNotExist();
    }

    @Test
    void wipingEmptiesTheFoldersButKeepsThem() throws Exception {
        Files.createDirectories(storage.trackFile(track).getParent());
        Files.write(storage.trackFile(track), new byte[]{1});

        storage.wipe();

        assertThat(storage.folder(Location.MUSIC)).isEmptyDirectory();
        assertThat(root.resolve("covers")).isEmptyDirectory();
    }

    @Test
    void theExtensionIsTakenFromTheFileName() {
        assertThat(MediaStorage.extensionOf("01 Ouvertyr.FLAC")).isEqualTo("flac");
        assertThat(MediaStorage.extensionOf("utan")).isEmpty();
    }
}
