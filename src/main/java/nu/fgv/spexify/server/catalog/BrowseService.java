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

package nu.fgv.spexify.server.catalog;

import nu.fgv.spexify.server.catalog.CatalogDtos.CreditDto;
import nu.fgv.spexify.server.catalog.CatalogDtos.TrackDto;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browsing the listed tracks by who and what they are credited to, and radio: tracks that go with a track or an album.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class BrowseService {

    private static final Pattern SEPARATORS = Pattern.compile("\\s*(?:[,;/&])\\s*");
    private static final Pattern ORIGINAL_ARTIST = Pattern.compile("\\(([^()]+)\\)\\s*$");
    private static final int MAX_RADIO = 200;

    private final TrackRepository trackRepository;
    private final CatalogService catalogService;
    private final CallerService callerService;
    private final RandomGenerator random;

    BrowseService(final TrackRepository trackRepository, final CatalogService catalogService, final CallerService callerService,
                  final RandomGenerator random) {
        this.trackRepository = trackRepository;
        this.catalogService = catalogService;
        this.callerService = callerService;
        this.random = random;
    }

    @Autowired
    public BrowseService(final TrackRepository trackRepository, final CatalogService catalogService, final CallerService callerService) {
        this(trackRepository, catalogService, callerService, new Random());
    }

    /**
     * The names a track is credited to for the kind, as written, without blanks or duplicates.
     */
    static List<String> names(final Track track, final CreditKind kind) {
        return switch (kind) {
            case PERFORMER -> split(track.getPerformers());
            case LYRICS_AUTHOR -> split(track.getLyricsAuthor());
            case ARRANGER -> split(track.getArranger());
            case MELODY -> StringUtils.hasText(track.getMelody()) ? List.of(track.getMelody().strip()) : List.of();
            case ORIGINAL_ARTIST -> originalArtist(track.getMelody());
        };
    }

    static List<String> split(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }

        final Set<String> seen = new HashSet<>();
        final List<String> names = new ArrayList<>();

        for (final String part : SEPARATORS.split(value.strip())) {
            final String name = part.strip();

            if (!name.isEmpty() && seen.add(key(name))) {
                names.add(name);
            }
        }
        return names;
    }

    static List<String> originalArtist(final @Nullable String melody) {
        if (!StringUtils.hasText(melody)) {
            return List.of();
        }

        final Matcher matcher = ORIGINAL_ARTIST.matcher(melody.strip());

        return matcher.find() ? split(matcher.group(1)) : List.of();
    }

    private static String key(final String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * How well a track goes with the seed: the same melody counts most, then the same original artist, shared
     * performers, authors and arrangers, and the same spex.
     */
    static int score(final Track seed, final Track track) {
        int score = 0;

        if (shares(seed, track, CreditKind.MELODY)) {
            score += 8;
        }
        if (shares(seed, track, CreditKind.ORIGINAL_ARTIST)) {
            score += 4;
        }
        if (shares(seed, track, CreditKind.PERFORMER)) {
            score += 3;
        }
        if (shares(seed, track, CreditKind.LYRICS_AUTHOR) || shares(seed, track, CreditKind.ARRANGER)) {
            score += 2;
        }
        if (sameSpex(seed.getAlbum(), track.getAlbum())) {
            score += 2;
        }
        return score;
    }

    /**
     * Whether the albums share a spex, counting revivals as their original.
     */
    private static boolean sameSpex(final Album a, final Album b) {
        return a.getSpex().stream().anyMatch(ref -> b.isAbout(ref.familyId()));
    }

    private static boolean shares(final Track a, final Track b, final CreditKind kind) {
        final Set<String> names = new HashSet<>(names(a, kind).stream().map(BrowseService::key).toList());

        return names(b, kind).stream().map(BrowseService::key).anyMatch(names::contains);
    }

    @Transactional(readOnly = true)
    public List<CreditDto> credits(final CreditKind kind) {
        callerService.current().requireUser();

        final Map<String, String> display = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();

        for (final Track track : trackRepository.findListed()) {
            for (final String name : names(track, kind)) {
                display.putIfAbsent(key(name), name);
                counts.merge(key(name), 1, Integer::sum);
            }
        }

        final Collator collator = Collator.getInstance(Locale.forLanguageTag("sv"));

        return counts.entrySet().stream()
                .map(entry -> new CreditDto(display.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(CreditDto::name, collator))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TrackDto> creditTracks(final CreditKind kind, final String name) {
        callerService.current().requireUser();

        final String wanted = key(name);

        return trackRepository.findListed().stream()
                .filter(track -> names(track, kind).stream().map(BrowseService::key).anyMatch(wanted::equals))
                .map(catalogService::toDto)
                .toList();
    }

    /**
     * Listed tracks to play one after another. Related tracks come first, in an order that differs every time; the rest
     * is filled up at random.
     */
    @Transactional(readOnly = true)
    public List<TrackDto> radio(final @Nullable UUID trackId, final @Nullable UUID albumId, final int limit) {
        callerService.current().requireUser();
        if (trackId != null && albumId != null) {
            throw new BadRequestException("error.radio.oneSeed");
        }

        final int size = Math.clamp(limit, 1, MAX_RADIO);
        final List<Track> listed = trackRepository.findListed();
        final List<Track> seeds = listed.stream()
                .filter(track -> track.getUuid().equals(trackId) || track.getAlbum().getUuid().equals(albumId))
                .toList();

        if ((trackId != null || albumId != null) && seeds.isEmpty()) {
            throw new ResourceNotFoundException(
                    trackId != null ? "error.track.notFound" : "error.album.notFound", Objects.requireNonNullElse(trackId, albumId));
        }

        final Set<Long> seedIds = new HashSet<>(seeds.stream().map(Track::getId).toList());
        final List<Track> candidates = new ArrayList<>(listed.stream().filter(track -> !seedIds.contains(track.getId())).toList());
        final Map<Long, Double> rank = new LinkedHashMap<>();

        for (final Track candidate : candidates) {
            final int score = seeds.stream().mapToInt(seed -> score(seed, candidate)).max().orElse(0);

            // A little chance keeps equally good tracks from always coming in the same order
            rank.put(candidate.getId(), score + random.nextDouble() * 1.5);
        }
        candidates.sort(Comparator.comparingDouble((Track track) -> rank.getOrDefault(track.getId(), 0.0)).reversed());

        final List<Track> result = new ArrayList<>();

        if (trackId != null) {
            result.addAll(seeds);
        }
        if (seeds.isEmpty()) {
            Collections.shuffle(candidates, random);
        }
        candidates.stream().limit(Math.max(size - result.size(), 0)).forEach(result::add);
        return result.stream().map(catalogService::toDto).toList();
    }
}
