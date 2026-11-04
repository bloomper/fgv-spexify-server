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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Where a track's file lies, as its album and the track are published, unpublished and trashed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class LibraryPlacerTest {

    @TempDir
    private Path root;
    @Mock
    private TagWriter tagWriter;
    private MediaStorage storage;
    private LibraryPlacer placer;
    private Album album;
    private Track track;

    @BeforeEach
    void setUp() throws Exception {
        storage = new MediaStorage(new SpexifyProperties(null, null, null, new SpexifyProperties.Media(root, DataSize.ofMegabytes(1), null, null, null),
                null, null, null, null, null));
        placer = new LibraryPlacer(storage, tagWriter);
        album = new Album();
        album.setTitle("Cleopatra");
        track = new Track();
        track.setAlbum(album);
        track.setTitle("Ouvertyr");
        track.setTrackNumber(1);
        track.setFileExtension("flac");
        track.setLocation(Location.UNPUBLISHED);
        album.getTracks().add(track);
        Files.createDirectories(storage.trackFile(track).getParent());
        Files.write(storage.trackFile(track), new byte[]{1, 2, 3});
    }

    @Test
    void anUnpublishedAlbumStaysOutOfNavidromesSight() {
        assertThat(placer.place(album)).isFalse();
        assertThat(track.getLocation()).isEqualTo(Location.UNPUBLISHED);
    }

    @Test
    void publishingMovesTheFileIntoTheMusicFolderWithTagsAndLyrics() throws Exception {
        album.setPublished(true);
        track.setLyrics("[00:01.00]Här står vi");
        track.setLyricsSynced(true);

        assertThat(placer.place(album)).isTrue();

        assertThat(track.getLocation()).isEqualTo(Location.MUSIC);
        assertThat(storage.trackFile(track)).hasBinaryContent(new byte[]{1, 2, 3});
        assertThat(storage.lyricsFile(track, Location.MUSIC, true)).hasContent("[00:01.00]Här står vi");
        assertThat(track.getTagsHash()).isNotNull();
        verify(tagWriter).write(track, storage.trackFile(track));
    }

    @Test
    void nothingIsWrittenAgainWhenNothingChanged() throws Exception {
        album.setPublished(true);
        placer.place(album);

        assertThat(placer.place(album)).isFalse();
        verify(tagWriter, times(1)).write(any(), any());

        track.setTitle("Uvertyr");
        assertThat(placer.place(album)).isTrue();
        verify(tagWriter, times(2)).write(any(), any());
    }

    @Test
    void trashingMovesTheFileToTheTrashAndForgetsNavidromesId() {
        album.setPublished(true);
        placer.place(album);
        track.setNavidromeId("nd-1");

        track.setTrashedAt(Instant.now());
        assertThat(placer.place(album)).isTrue();

        assertThat(track.getLocation()).isEqualTo(Location.TRASH);
        assertThat(track.getNavidromeId()).isNull();
        assertThat(storage.trackFile(track)).exists();
        assertThat(storage.lyricsFile(track, Location.MUSIC, false)).doesNotExist();
    }

    @Test
    void aMissingFileIsSkippedWithoutFailingTheRest() throws Exception {
        Files.delete(storage.trackFile(track));
        album.setPublished(true);

        assertThat(placer.place(album)).isFalse();
        assertThat(track.getLocation()).isEqualTo(Location.UNPUBLISHED);
        verify(tagWriter, never()).write(any(), any());
    }
}
