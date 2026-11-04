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
package nu.fgv.spexify.server.listening;

import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumRepository;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.navidrome.NavidromeClient;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.AppUserRepository;
import nu.fgv.spexify.server.user.CallerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirroring everyone's ratings, likes and plays from Navidrome.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ListeningServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Mock
    private TrackListeningRepository trackListeningRepository;
    @Mock
    private AlbumListeningRepository albumListeningRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private CatalogService catalogService;
    @Mock
    private CallerService callerService;
    @Mock
    private NavidromeClient navidrome;
    @Mock
    private PlatformTransactionManager transactionManager;
    private ListeningService service;
    private AppUser lisa;
    private Track track;
    private Album album;

    private static Map<String, String[]> parameters(final String... pairs) {
        final Map<String, String[]> parameters = new java.util.LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            final String key = pairs[i];
            final String value = pairs[i + 1];

            parameters.merge(key, new String[]{value}, (first, second) -> {
                final String[] both = java.util.Arrays.copyOf(first, first.length + 1);

                both[first.length] = second[0];
                return both;
            });
        }
        return parameters;
    }

    @BeforeEach
    void setUp() {
        service = new ListeningService(trackListeningRepository, albumListeningRepository, trackRepository, albumRepository,
                userRepository, catalogService, callerService, navidrome, new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lisa = new AppUser();
        lisa.setId(5L);
        lisa.setUsername("lisa@spexify.test");
        album = new Album();
        album.setId(2L);
        album.setNavidromeId("nd-album");
        track = new Track();
        track.setId(11L);
        track.setNavidromeId("nd-song");
        lenient().when(userRepository.findByUsername("lisa@spexify.test")).thenReturn(Optional.of(lisa));
        lenient().when(trackRepository.findByNavidromeIdIn(List.of("nd-song"))).thenReturn(List.of(track));
        lenient().when(albumRepository.findByNavidromeIdIn(List.of("nd-album"))).thenReturn(List.of(album));
        lenient().when(trackListeningRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(albumListeningRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void aRatingOfASongOrAnAlbumIsRecordedAndZeroRemovesIt() {
        final TrackListening songState = new TrackListening();
        final AlbumListening albumState = new AlbumListening();

        when(trackListeningRepository.findByUserIdAndTrackId(5L, 11L)).thenReturn(Optional.of(songState));
        when(albumListeningRepository.findByUserIdAndAlbumId(5L, 2L)).thenReturn(Optional.of(albumState));

        service.accepted("lisa@spexify.test", "setrating", parameters("id", "nd-song", "rating", "4"));
        service.accepted("lisa@spexify.test", "setrating", parameters("id", "nd-album", "rating", "3"));
        assertThat(songState.getRating()).isEqualTo(4);
        assertThat(albumState.getRating()).isEqualTo(3);

        service.accepted("lisa@spexify.test", "setrating", parameters("id", "nd-song", "rating", "0"));
        assertThat(songState.getRating()).isNull();
    }

    @Test
    void likesOfSongsAndAlbumsAreRecordedAndTakenBack() {
        final TrackListening songState = new TrackListening();
        final AlbumListening albumState = new AlbumListening();

        when(trackListeningRepository.findByUserIdAndTrackId(5L, 11L)).thenReturn(Optional.of(songState));
        when(albumListeningRepository.findByUserIdAndAlbumId(5L, 2L)).thenReturn(Optional.of(albumState));

        service.accepted("lisa@spexify.test", "star", parameters("id", "nd-song", "albumId", "nd-album"));
        assertThat(songState.getLikedAt()).isEqualTo(NOW);
        assertThat(albumState.getLikedAt()).isEqualTo(NOW);

        service.accepted("lisa@spexify.test", "unstar", parameters("id", "nd-song"));
        assertThat(songState.getLikedAt()).isNull();
    }

    @Test
    void submittedPlaysAreCountedButNowPlayingIsNot() {
        final TrackListening songState = new TrackListening();
        final Instant played = Instant.parse("2026-11-03T20:00:00Z");

        when(trackListeningRepository.findByUserIdAndTrackId(5L, 11L)).thenReturn(Optional.of(songState));

        service.accepted("lisa@spexify.test", "scrobble", parameters("id", "nd-song", "time", Long.toString(played.toEpochMilli())));
        service.accepted("lisa@spexify.test", "scrobble", parameters("id", "nd-song"));
        service.accepted("lisa@spexify.test", "scrobble", parameters("id", "nd-song", "submission", "false"));

        assertThat(songState.getPlayCount()).isEqualTo(2);
        assertThat(songState.getLastPlayedAt()).isEqualTo(NOW);
    }

    @Test
    void unknownUsersAndSongsAreIgnored() {
        service.accepted("someone@spexify.test", "setrating", parameters("id", "nd-song", "rating", "4"));
        service.accepted("lisa@spexify.test", "setrating", parameters("id", "nd-unknown", "rating", "4"));

        verify(trackListeningRepository, never()).save(any());
        verify(albumListeningRepository, never()).save(any());
    }

    @Test
    void theNightlyCopyReplacesWhatTheMirrorHad() throws IOException {
        final TrackListening stale = new TrackListening();

        stale.setTrackId(99L);
        when(userRepository.findAll()).thenReturn(List.of(lisa));
        when(navidrome.systemUser()).thenReturn("spexify-system");
        when(navidrome.songStates("lisa@spexify.test")).thenReturn(List.of(new NavidromeClient.SongState("nd-song", 5, NOW, 7, NOW)));
        when(navidrome.albumStates("lisa@spexify.test")).thenReturn(List.of());
        when(trackListeningRepository.findByUserId(5L)).thenReturn(List.of(stale));

        service.sync();

        verify(trackListeningRepository).save(org.mockito.ArgumentMatchers.argThat(state -> state.getTrackId() == 11L
                && state.getRating() == 5 && NOW.equals(state.getLikedAt()) && state.getPlayCount() == 7));
        verify(trackListeningRepository).delete(stale);
    }

    @Test
    void aUserNavidromeCannotAnswerForIsSkipped() throws IOException {
        final AppUser other = new AppUser();

        other.setId(6L);
        other.setUsername("olle@spexify.test");
        when(userRepository.findAll()).thenReturn(List.of(other, lisa));
        when(navidrome.systemUser()).thenReturn("spexify-system");
        when(navidrome.songStates("olle@spexify.test")).thenThrow(new IOException("down"));
        when(navidrome.songStates("lisa@spexify.test")).thenReturn(List.of());
        when(navidrome.albumStates("lisa@spexify.test")).thenReturn(List.of());

        service.sync();

        verify(trackListeningRepository).findByUserId(5L);
        verify(trackListeningRepository, never()).findByUserId(6L);
    }

    @Test
    void theWeightedAverageDrawsFewRatingsTowardsTheMean() {
        final UUID single = UUID.randomUUID();
        final UUID many = UUID.randomUUID();
        final UUID low = UUID.randomUUID();
        final Map<UUID, Double> scores = ListeningService.weighted(Map.of(
                single, new ListeningService.Totals(5, 1, 0, 0),
                many, new ListeningService.Totals(40, 10, 0, 0),
                low, new ListeningService.Totals(6, 2, 0, 0),
                UUID.randomUUID(), new ListeningService.Totals(0, 0, 3, 10)));

        // The mean of all 13 ratings is about 3.9: a single 5 comes out near 4.3, ten 4s near 4.0, two 3s near 3.5
        assertThat(scores).hasSize(3);
        assertThat(scores.get(single)).isLessThan(4.5).isGreaterThan(scores.get(many));
        assertThat(scores.get(many)).isGreaterThan(scores.get(low));
    }
}
