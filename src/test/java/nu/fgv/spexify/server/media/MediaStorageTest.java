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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

    private static void inTransaction(final int status, final ThrowingRunnable action) throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            action.run();
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCompletion(status));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Path oldFile() throws Exception {
        Files.createDirectories(storage.trackFile(track).getParent());
        return Files.writeString(storage.trackFile(track), "old");
    }

    @Test
    void replacedAudioTakesThePlaceOfTheOldOnACommit() throws Exception {
        final Path old = oldFile();
        final Path upload = Files.writeString(storage.uploadFile("u-1"), "new");

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> {
            track.setFileExtension("mp3");
            storage.replace(upload, track, old, upload);
        });

        assertThat(storage.trackFile(track)).hasContent("new");
        assertThat(old).doesNotExist();
        assertThat(upload).doesNotExist();
        assertThat(root.resolve("uploads")).isEmptyDirectory();
    }

    @Test
    void onARollbackTheOldAudioIsPutBackAndTheNewReturnsToItsUpload() throws Exception {
        final Path old = oldFile();
        final Path upload = Files.writeString(storage.uploadFile("u-1"), "new");

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> storage.replace(upload, track, old, upload));

        assertThat(old).hasContent("old");
        assertThat(upload).hasContent("new");
        assertThat(root.resolve("uploads")).isDirectoryContaining(path -> path.equals(upload))
                .isDirectoryNotContaining(path -> path.toString().endsWith(".replaced"));
    }

    @Test
    void aNewTracksAudioReturnsToItsUploadOnARollback() throws Exception {
        final Path upload = Files.writeString(storage.uploadFile("u-1"), "new");

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> storage.storeNew(upload, track, upload));

        assertThat(storage.trackFile(track)).doesNotExist();
        assertThat(upload).hasContent("new");
    }

    @Test
    void aNewTracksAudioStaysOnACommit() throws Exception {
        final Path upload = Files.writeString(storage.uploadFile("u-1"), "new");

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> storage.storeNew(upload, track, upload));

        assertThat(storage.trackFile(track)).hasContent("new");
        assertThat(upload).doesNotExist();
    }

    @Test
    void deletingWaitsForTheCommit() throws Exception {
        final Path old = oldFile();

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> {
            storage.deleteAfterCommit(track);
            storage.deleteAlbumAfterCommit(album);
        });
        assertThat(old).exists();
        assertThat(storage.coverFile(album)).exists();

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> storage.deleteAfterCommit(track));
        assertThat(old).doesNotExist();

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> storage.deleteAlbumAfterCommit(album));
        assertThat(storage.coverFile(album)).doesNotExist();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void aNewCoverReplacesTheOldOnACommit() throws Exception {
        final Path old = storage.coverFile(album);

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> {
            album.setCoverExtension("jpg");
            storage.replaceCover(album, "png", new byte[]{7});
        });

        assertThat(storage.coverFile(album)).hasBinaryContent(new byte[]{7});
        assertThat(old).doesNotExist();
        assertThat(root.resolve("uploads")).isEmptyDirectory();
    }

    @Test
    void onARollbackTheOldCoverIsPutBackAlsoBesideTheTracks() throws Exception {
        Files.createDirectories(storage.trackFile(track).getParent());
        Files.write(storage.trackFile(track), new byte[]{1});
        storage.syncCover(album, Location.MUSIC);

        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> {
            album.setCoverExtension("jpg");
            storage.replaceCover(album, "png", new byte[]{7});
            storage.syncCover(album, Location.MUSIC);
        });

        assertThat(album.getCoverExtension()).isEqualTo("png");
        assertThat(storage.coverFile(album)).hasBinaryContent(new byte[]{9});
        assertThat(root.resolve("covers").resolve(album.getUuid() + ".jpg")).doesNotExist();
        assertThat(storage.albumFolder(album, Location.MUSIC).resolve("cover.png")).hasBinaryContent(new byte[]{9});
        assertThat(storage.albumFolder(album, Location.MUSIC).resolve("cover.jpg")).doesNotExist();
        assertThat(root.resolve("uploads")).isEmptyDirectory();
    }

    @Test
    void aNewCoverWithTheSameExtensionDoesNotMixWithTheOldOnARollback() throws Exception {
        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> storage.replaceCover(album, "png", new byte[]{7}));

        assertThat(storage.coverFile(album)).hasBinaryContent(new byte[]{9});
    }

    @Test
    void aRemovedCoverComesBackOnARollbackAndIsGoneOnACommit() throws Exception {
        inTransaction(TransactionSynchronization.STATUS_ROLLED_BACK, () -> {
            album.setCoverExtension(null);
            storage.replaceCover(album, "png", null);
        });
        assertThat(storage.coverFile(album)).hasBinaryContent(new byte[]{9});

        inTransaction(TransactionSynchronization.STATUS_COMMITTED, () -> {
            album.setCoverExtension(null);
            storage.replaceCover(album, "png", null);
        });
        assertThat(root.resolve("covers")).isEmptyDirectory();
        assertThat(root.resolve("uploads")).isEmptyDirectory();
    }

    @Test
    void theExtensionIsTakenFromTheFileName() {
        assertThat(MediaStorage.extensionOf("01 Ouvertyr.FLAC")).isEqualTo("flac");
        assertThat(MediaStorage.extensionOf("utan")).isEmpty();
    }
}
