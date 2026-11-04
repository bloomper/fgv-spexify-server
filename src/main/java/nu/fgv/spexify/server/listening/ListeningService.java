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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumRepository;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumFilter;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumSort;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.navidrome.NavidromeClient;
import nu.fgv.spexify.server.navidrome.SubsonicActivityListener;
import nu.fgv.spexify.server.user.AppUser;
import nu.fgv.spexify.server.user.AppUserRepository;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everyone's ratings, likes and plays. Navidrome keeps them per user only, so Spexify mirrors them: the Subsonic gateway
 * reports every change Navidrome accepts, from the web or an app ({@link #accepted}), and {@link #sync()} copies
 * Navidrome's state every night, which also fills the mirror the first time and mends anything missed. Each user's own
 * state is still read from Navidrome; the mirror gives the averages, counts and top lists.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class ListeningService implements SubsonicActivityListener {

    /**
     * How many average votes a rating is weighed against in the top lists and the rating order, so that a single 5
     * does not top them: {@code (PRIOR * mean + sum) / (PRIOR + count)}, where mean is the average of every rating.
     */
    static final int PRIOR = 2;
    private static final int MAX_TOP = 100;

    private final TrackListeningRepository trackListeningRepository;
    private final AlbumListeningRepository albumListeningRepository;
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final AppUserRepository userRepository;
    private final CatalogService catalogService;
    private final CallerService callerService;
    private final NavidromeClient navidrome;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ListeningService(final TrackListeningRepository trackListeningRepository, final AlbumListeningRepository albumListeningRepository,
                            final TrackRepository trackRepository, final AlbumRepository albumRepository,
                            final AppUserRepository userRepository, final CatalogService catalogService,
                            final CallerService callerService, final NavidromeClient navidrome,
                            final TransactionTemplate transactionTemplate, final Clock clock) {
        this.trackListeningRepository = trackListeningRepository;
        this.albumListeningRepository = albumListeningRepository;
        this.trackRepository = trackRepository;
        this.albumRepository = albumRepository;
        this.userRepository = userRepository;
        this.catalogService = catalogService;
        this.callerService = callerService;
        this.navidrome = navidrome;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    private static List<String> values(final Map<String, String[]> parameters, final String name) {
        final String[] values = parameters.get(name);

        return values == null ? List.of() : List.of(values);
    }

    private static @Nullable Integer parseInt(final @Nullable String value) {
        try {
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Weighted averages from rows of key, sum and count: each average drawn towards the mean of all ratings. Rows
     * without ratings are left out.
     */
    static <K> Map<K, Double> weighted(final Map<K, Totals> totals) {
        double sum = 0;
        long count = 0;

        for (final Totals entry : totals.values()) {
            sum += entry.ratingSum();
            count += entry.ratingCount();
        }

        final double mean = count == 0 ? 0 : sum / count;
        final Map<K, Double> scores = new HashMap<>();

        totals.forEach((key, entry) -> {
            if (entry.ratingCount() > 0) {
                scores.put(key, (PRIOR * mean + entry.ratingSum()) / (PRIOR + entry.ratingCount()));
            }
        });
        return scores;
    }

    private static Map<UUID, Totals> totals(final List<Object[]> rows, final boolean plays) {
        final Map<UUID, Totals> totals = new HashMap<>();

        for (final Object[] row : rows) {
            totals.put((UUID) row[0], new Totals(number(row[1]), (int) number(row[2]), (int) number(row[3]),
                    plays ? number(row[4]) : 0));
        }
        return totals;
    }

    private static long number(final @Nullable Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static @Nullable Long parseLong(final String value) {
        try {
            return Long.valueOf(value.strip());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Comparator<AlbumDto> byScore(final Map<UUID, Double> scores) {
        return Comparator.comparingDouble((AlbumDto album) -> scores.getOrDefault(album.id(), -1.0)).reversed();
    }

    private static @Nullable AlbumFilter listed(final @Nullable AlbumFilter filter) {
        return filter == null ? null : new AlbumFilter(filter.categoryId(), filter.year(), filter.spexId(), false, filter.kindId(),
                filter.fromYear(), filter.toYear(), null, null);
    }

    private static Map<UUID, Double> scores(final TopBy by, final Map<UUID, Totals> totals) {
        if (by == TopBy.RATED) {
            return weighted(totals);
        }

        final Map<UUID, Double> scores = new HashMap<>();

        totals.forEach((id, entry) -> {
            final long value = by == TopBy.LIKED ? entry.likeCount() : entry.playCount();

            if (value > 0) {
                scores.put(id, (double) value);
            }
        });
        return scores;
    }

    @Override
    public void accepted(final String username, final String endpoint, final Map<String, String[]> parameters) {
        transactionTemplate.executeWithoutResult(_ -> userRepository.findByUsername(username).ifPresent(user -> {
            switch (endpoint) {
                case "setrating" -> {
                    final Integer rating = parseInt(values(parameters, "rating").stream().findFirst().orElse(null));

                    if (rating != null && rating >= 0 && rating <= 5) {
                        values(parameters, "id").forEach(id -> rate(user, id, rating));
                    }
                }
                case "star", "unstar" -> {
                    final Instant at = "star".equals(endpoint) ? clock.instant() : null;

                    values(parameters, "id").forEach(id -> like(user, id, at, true));
                    values(parameters, "albumId").forEach(id -> like(user, id, at, false));
                }
                case "scrobble" -> {
                    if (!"false".equalsIgnoreCase(values(parameters, "submission").stream().findFirst().orElse("true"))) {
                        final List<String> ids = values(parameters, "id");
                        final List<String> times = values(parameters, "time");

                        for (int i = 0; i < ids.size(); i++) {
                            final Long millis = i < times.size() ? parseLong(times.get(i)) : null;

                            played(user, ids.get(i), millis == null ? clock.instant() : Instant.ofEpochMilli(millis));
                        }
                    }
                }
                default -> {
                    // Not about listening
                }
            }
        }));
    }

    private void rate(final AppUser user, final String navidromeId, final int rating) {
        final Integer value = rating == 0 ? null : rating;
        final Optional<Track> track = track(navidromeId);

        if (track.isPresent()) {
            trackState(user, track.get()).setRating(value);
        } else {
            album(navidromeId).ifPresent(album -> albumState(user, album).setRating(value));
        }
    }

    private void like(final AppUser user, final String navidromeId, final @Nullable Instant at, final boolean songFirst) {
        final Optional<Track> track = songFirst ? track(navidromeId) : Optional.empty();

        if (track.isPresent()) {
            trackState(user, track.get()).setLikedAt(at);
        } else {
            album(navidromeId).ifPresent(album -> albumState(user, album).setLikedAt(at));
        }
    }

    private void played(final AppUser user, final String navidromeId, final Instant at) {
        track(navidromeId).ifPresent(track -> {
            final TrackListening state = trackState(user, track);

            state.setPlayCount(state.getPlayCount() + 1);
            if (state.getLastPlayedAt() == null || at.isAfter(state.getLastPlayedAt())) {
                state.setLastPlayedAt(at);
            }
        });
    }

    private Optional<Track> track(final String navidromeId) {
        return trackRepository.findByNavidromeIdIn(List.of(navidromeId)).stream().findFirst();
    }

    private Optional<Album> album(final String navidromeId) {
        return albumRepository.findByNavidromeIdIn(List.of(navidromeId)).stream().findFirst();
    }

    private TrackListening trackState(final AppUser user, final Track track) {
        return trackListeningRepository.findByUserIdAndTrackId(user.getId(), track.getId()).orElseGet(() -> {
            final TrackListening created = new TrackListening();

            created.setUserId(user.getId());
            created.setTrackId(track.getId());
            return trackListeningRepository.save(created);
        });
    }

    private AlbumListening albumState(final AppUser user, final Album album) {
        return albumListeningRepository.findByUserIdAndAlbumId(user.getId(), album.getId()).orElseGet(() -> {
            final AlbumListening created = new AlbumListening();

            created.setUserId(user.getId());
            created.setAlbumId(album.getId());
            return albumListeningRepository.save(created);
        });
    }

    /**
     * Copies every user's ratings, likes and plays from Navidrome. A user Navidrome cannot answer for is skipped and
     * tried again the next night.
     */
    public void sync() {
        int synced = 0;

        for (final AppUser user : userRepository.findAll()) {
            if (user.getUsername().equalsIgnoreCase(navidrome.systemUser())) {
                continue;
            }
            try {
                final List<NavidromeClient.SongState> songs = navidrome.songStates(user.getUsername());
                final List<NavidromeClient.AlbumState> albums = navidrome.albumStates(user.getUsername());

                transactionTemplate.executeWithoutResult(_ -> replace(user, songs, albums));
                synced++;
            } catch (final IOException e) {
                log.warn("Could not copy what {} listened to from Navidrome: {}", user.getUsername(), e.getMessage());
            }
        }
        log.info("Copied ratings, likes and plays of {} users from Navidrome", synced);
    }

    private void replace(final AppUser user, final List<NavidromeClient.SongState> songs, final List<NavidromeClient.AlbumState> albums) {
        final Map<String, Track> tracks = songs.isEmpty() ? Map.of()
                : trackRepository.findByNavidromeIdIn(songs.stream().map(NavidromeClient.SongState::id).toList()).stream()
                .collect(Collectors.toMap(Track::getNavidromeId, Function.identity(), (first, _) -> first));
        final Map<Long, TrackListening> trackStates = trackListeningRepository.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(TrackListening::getTrackId, Function.identity()));
        final Set<Long> seenTracks = new HashSet<>();

        for (final NavidromeClient.SongState song : songs) {
            final Track track = tracks.get(song.id());

            if (track == null || !seenTracks.add(track.getId())) {
                continue;
            }

            final TrackListening state = trackStates.computeIfAbsent(track.getId(), id -> {
                final TrackListening created = new TrackListening();

                created.setUserId(user.getId());
                created.setTrackId(id);
                return created;
            });

            state.setRating(song.rating() > 0 ? song.rating() : null);
            state.setLikedAt(song.starred());
            state.setPlayCount(song.playCount());
            state.setLastPlayedAt(song.played());
            trackListeningRepository.save(state);
        }
        trackStates.values().stream().filter(state -> !seenTracks.contains(state.getTrackId())).forEach(trackListeningRepository::delete);

        final Map<String, Album> albumsById = albums.isEmpty() ? Map.of()
                : albumRepository.findByNavidromeIdIn(albums.stream().map(NavidromeClient.AlbumState::id).toList()).stream()
                .collect(Collectors.toMap(Album::getNavidromeId, Function.identity(), (first, _) -> first));
        final Map<Long, AlbumListening> albumStates = albumListeningRepository.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(AlbumListening::getAlbumId, Function.identity()));
        final Set<Long> seenAlbums = new HashSet<>();

        for (final NavidromeClient.AlbumState entry : albums) {
            final Album album = albumsById.get(entry.id());

            if (album == null || !seenAlbums.add(album.getId())) {
                continue;
            }

            final AlbumListening state = albumStates.computeIfAbsent(album.getId(), id -> {
                final AlbumListening created = new AlbumListening();

                created.setUserId(user.getId());
                created.setAlbumId(id);
                return created;
            });

            state.setRating(entry.rating() > 0 ? entry.rating() : null);
            state.setLikedAt(entry.starred());
            albumListeningRepository.save(state);
        }
        albumStates.values().stream().filter(state -> !seenAlbums.contains(state.getAlbumId())).forEach(albumListeningRepository::delete);
    }

    /**
     * Everyone's ratings, likes and plays of some tracks; tracks nobody did anything with are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Totals> trackTotals(final Collection<UUID> trackIds) {
        return trackIds.isEmpty() ? Map.of() : totals(trackListeningRepository.totals(trackIds), true);
    }

    /**
     * Everyone's ratings and likes of some albums, and plays of their tracks; albums nobody did anything with are left
     * out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Totals> albumTotals(final Collection<UUID> albumIds) {
        if (albumIds.isEmpty()) {
            return Map.of();
        }

        final Map<UUID, Totals> totals = new HashMap<>(totals(albumListeningRepository.totals(albumIds), false));
        final Set<UUID> wanted = new HashSet<>(albumIds);

        for (final Object[] row : trackListeningRepository.albumPlays()) {
            final UUID album = (UUID) row[0];

            if (wanted.contains(album)) {
                final Totals entry = totals.getOrDefault(album, Totals.NONE);

                totals.put(album, new Totals(entry.ratingSum(), entry.ratingCount(), entry.likeCount(), number(row[1])));
            }
        }
        return totals;
    }

    /**
     * The listed tracks of the albums the filter lists, ranked by everyone. Tracks without any are left out.
     */
    @Transactional(readOnly = true)
    public List<TrackDto> topTracks(final TopBy by, final @Nullable AlbumFilter filter, final int limit) {
        final Map<UUID, Double> scores = scores(by, totals(trackListeningRepository.totals(), true));

        return catalogService.listedTracks(filter).stream()
                .filter(track -> scores.containsKey(track.id()))
                .sorted(Comparator.comparingDouble((TrackDto track) -> scores.get(track.id())).reversed())
                .limit(Math.clamp(limit, 1, MAX_TOP))
                .toList();
    }

    /**
     * The listed albums the filter lists, ranked by everyone. Albums without any are left out.
     */
    @Transactional(readOnly = true)
    public List<AlbumDto> topAlbums(final TopBy by, final @Nullable AlbumFilter filter, final int limit) {
        final Map<UUID, Double> scores = scores(by, allAlbumTotals());

        return catalogService.albums(listed(filter)).stream()
                .filter(album -> scores.containsKey(album.id()))
                .sorted(Comparator.comparingDouble((AlbumDto album) -> scores.get(album.id())).reversed())
                .limit(Math.clamp(limit, 1, MAX_TOP))
                .toList();
    }

    /**
     * The albums the filter lists, also narrowed to what the caller likes, and in the asked order.
     */
    @Transactional
    public List<AlbumDto> browse(final @Nullable AlbumFilter filter) {
        List<AlbumDto> albums = catalogService.albums(filter);

        if (filter == null) {
            return albums;
        }
        if (Boolean.TRUE.equals(filter.liked())) {
            final Caller caller = callerService.current();

            caller.requireUser();

            final Set<UUID> liked = new HashSet<>(albumListeningRepository.likedAlbums(callerService.record(caller).getId()));

            albums = albums.stream().filter(album -> liked.contains(album.id())).toList();
        }

        final AlbumSort sort = filter.sort();

        if (sort == null || sort == AlbumSort.YEAR_DESC) {
            return albums;
        }

        final Comparator<AlbumDto> order = switch (sort) {
            case YEAR_DESC -> (first, second) -> 0;
            case YEAR_ASC -> Comparator.comparing(AlbumDto::year, Comparator.nullsLast(Comparator.naturalOrder()));
            case TITLE -> Comparator.comparing(album -> album.title().toLowerCase(Locale.ROOT));
            case PUBLISHED ->
                    Comparator.comparing(AlbumDto::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case RATING -> byScore(scores(TopBy.RATED, allAlbumTotals()));
            case LIKES -> byScore(scores(TopBy.LIKED, allAlbumTotals()));
            case PLAYS -> byScore(scores(TopBy.PLAYED, allAlbumTotals()));
        };

        // The list comes newest year first, then by title, which a stable sort keeps for ties
        return albums.stream().sorted(order).toList();
    }

    private Map<UUID, Totals> allAlbumTotals() {
        final Map<UUID, Totals> totals = new HashMap<>(totals(albumListeningRepository.totals(), false));

        for (final Object[] row : trackListeningRepository.albumPlays()) {
            final Totals entry = totals.getOrDefault((UUID) row[0], Totals.NONE);

            totals.put((UUID) row[0], new Totals(entry.ratingSum(), entry.ratingCount(), entry.likeCount(), number(row[1])));
        }
        return totals;
    }

    /**
     * What a top list ranks by.
     */
    public enum TopBy {
        /**
         * Everyone's ratings, as a weighted average.
         */
        RATED,
        /**
         * How many like it.
         */
        LIKED,
        /**
         * How often everyone played it.
         */
        PLAYED
    }

    /**
     * Everyone's ratings, likes and plays of a track or album.
     */
    public record Totals(long ratingSum, int ratingCount, int likeCount, long playCount) {

        public static final Totals NONE = new Totals(0, 0, 0, 0);

        public @Nullable Double average() {
            return ratingCount == 0 ? null : (double) ratingSum / ratingCount;
        }
    }
}
