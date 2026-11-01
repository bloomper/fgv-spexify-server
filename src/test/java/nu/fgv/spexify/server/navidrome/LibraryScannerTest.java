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

import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.Location;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.media.MediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asking Navidrome to scan, and matching its songs back to tracks afterwards.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class LibraryScannerTest {

    private static final String UUID = "0b6c1e1e-2c58-3d3a-9a51-5c3b1a2f9e10";
    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    @Mock
    private NavidromeClient client;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private MediaStorage storage;
    @Mock
    private PlatformTransactionManager transactionManager;
    private LibraryScanner scanner;
    private Track track;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        scanner = new LibraryScanner(client, trackRepository, storage, new TransactionTemplate(transactionManager), clock);
        track = new Track();
        track.setUuid(UUID);
        track.setAlbum(new Album());
        track.setLocation(Location.MUSIC);
        lenient().when(trackRepository.findByLocation(Location.MUSIC)).thenReturn(List.of(track));
    }

    @Test
    void aPendingScanStartsOnTheNextTickAndIdsAreMatchedWhenItEnds() throws IOException {
        when(client.scanStatus()).thenReturn(new NavidromeClient.ScanStatus(false, 0, null));

        scanner.tick();
        verify(client).startScan(false);
        assertThat(scanner.status().scanning()).isTrue();
        assertThat(scanner.status().pending()).isFalse();

        when(client.songs()).thenReturn(List.of(new NavidromeClient.Song("nd-1", "al-1", "music/a/" + UUID + ".flac", "")));
        scanner.tick();

        assertThat(track.getNavidromeId()).isEqualTo("nd-1");
        assertThat(track.getAlbum().getNavidromeId()).isEqualTo("al-1");
        assertThat(scanner.status().lastScanAt()).isEqualTo(clock.instant());
        assertThat(scanner.status().scanning()).isFalse();
    }

    @Test
    void nothingStartsWhileNavidromeIsScanning() throws IOException {
        when(client.scanStatus()).thenReturn(new NavidromeClient.ScanStatus(true, 10, null));

        scanner.tick();

        verify(client, never()).startScan(anyBoolean());
        assertThat(scanner.status().scanning()).isTrue();
    }

    @Test
    void aPublishedTrackNavidromeMissedLeadsToAFullScanAFewTimes() throws IOException {
        when(client.songs()).thenReturn(List.of());
        when(storage.exists(track)).thenReturn(true);

        for (int i = 0; i < 4; i++) {
            scanner.matchIds();
        }

        assertThat(track.getNavidromeId()).isNull();
        assertThat(scanner.status().pending()).isTrue();
        when(client.scanStatus()).thenReturn(new NavidromeClient.ScanStatus(false, 0, null));
        scanner.tick();
        verify(client).startScan(true);
    }

    @Test
    void anUnreachableNavidromeIsShownWithItsError() throws IOException {
        when(client.scanStatus()).thenThrow(new IOException("Connection refused"));

        scanner.tick();

        assertThat(scanner.status().available()).isFalse();
        assertThat(scanner.status().lastError()).isEqualTo("Connection refused");
    }

    @Test
    void onStartNavidromeIsAskedOnce() throws IOException {
        scanner.onReady();
        assertThat(scanner.status().available()).isTrue();
    }

    @Test
    void songsAreMatchedByPathOrMusicBrainzId() {
        assertThat(LibraryScanner.trackUuid(new NavidromeClient.Song("1", "", "a/" + UUID + ".mp3", ""))).isEqualTo(UUID);
        assertThat(LibraryScanner.trackUuid(new NavidromeClient.Song("1", "", "Album/01 Ouvertyr.mp3", UUID))).isEqualTo(UUID);
        assertThat(LibraryScanner.trackUuid(new NavidromeClient.Song("1", "", "Album/01 Ouvertyr.mp3", ""))).isNull();
    }
}
