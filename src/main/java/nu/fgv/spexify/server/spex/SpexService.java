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

package nu.fgv.spexify.server.spex;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumRepository;
import nu.fgv.spexify.server.catalog.SpexRef;
import nu.fgv.spexify.server.config.CacheConfig;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.SpexipediaUnavailableException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Picking the spex of an album, posters, premieres, and the daily refresh of the spex titles, years, originals and categories
 * copied onto albums.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class SpexService {

    private static final int MAX_RESULTS = 30;

    private final SpexipediaClient client;
    private final AlbumRepository albumRepository;
    private final CallerService callerService;
    private final Clock clock;

    public SpexService(final SpexipediaClient client, final AlbumRepository albumRepository,
                       final CallerService callerService, final Clock clock) {
        this.client = client;
        this.albumRepository = albumRepository;
        this.callerService = callerService;
        this.clock = clock;
    }

    /**
     * The matching spex grouped by original, newest original first: each original, then its matching revivals by
     * year. At most {@code limit} spex, but never splitting a group.
     */
    static List<SpexDto> group(final List<SpexDto> all, final Predicate<SpexDto> matches, final int limit) {
        final Map<UUID, SpexDto> byId = all.stream().collect(Collectors.toMap(SpexDto::id, Function.identity(), (a, _) -> a));
        final Map<UUID, List<SpexDto>> revivals = new LinkedHashMap<>();
        final Set<UUID> originals = new LinkedHashSet<>();

        for (final SpexDto spex : all) {
            if (!matches.test(spex)) {
                continue;
            }
            if (spex.parentId() != null && byId.containsKey(spex.parentId())) {
                originals.add(spex.parentId());
                revivals.computeIfAbsent(spex.parentId(), _ -> new ArrayList<>()).add(spex);
            } else {
                originals.add(spex.id());
            }
        }

        final Comparator<SpexDto> byYear = Comparator.comparing((SpexDto spex) -> spex.year() == null ? 0 : spex.year());
        final List<SpexDto> result = new ArrayList<>();

        for (final SpexDto original : originals.stream().map(byId::get).sorted(byYear.reversed()).toList()) {
            final List<SpexDto> group = new ArrayList<>();

            group.add(original);
            revivals.getOrDefault(original.id(), List.of()).stream().sorted(byYear).forEach(group::add);
            if (!result.isEmpty() && result.size() + group.size() > limit) {
                break;
            }
            result.addAll(group);
        }
        return result;
    }

    /**
     * Spex whose title or year matches, for editors picking the spex of an album. Revivals come right after their
     * original, which is included even when only a revival matches, so that they can be shown together.
     */
    public List<SpexDto> search(final String query) {
        callerService.current().requireEditor();

        final String q = query.strip().toLowerCase(Locale.ROOT);

        try {
            return group(client.allSpex(), spex -> q.isEmpty()
                    || (spex.title() != null && spex.title().toLowerCase(Locale.ROOT).contains(q))
                    || (spex.year() != null && spex.year().toString().equals(q)), MAX_RESULTS);
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            throw new SpexipediaUnavailableException(e);
        }
    }

    public SpexDto byId(final UUID id) {
        try {
            return client.spexById(id).orElseThrow(() -> new nu.fgv.spexify.server.util.error.BadRequestException("error.spex.notFound", id));
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            throw new SpexipediaUnavailableException(e);
        }
    }

    @Cacheable(cacheNames = CacheConfig.SPEX_POSTERS)
    public Optional<SpexipediaClient.Poster> poster(final UUID spexId) {
        try {
            return client.poster(spexId);
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            log.debug("Could not read the poster of spex {}", spexId, e);
            return Optional.empty();
        }
    }

    /**
     * The spex whose premiere was on today's date, in Spexify's time zone, in an earlier year; empty when Spexipedia is
     * unavailable. Only published pages in Spexipedia have a premiere.
     */
    public Set<UUID> premieredToday() {
        final LocalDate today = LocalDate.now(clock);

        try {
            return client.allSpex().stream()
                    .filter(spex -> premiereOf(spex).filter(date -> date.getYear() < today.getYear()
                            && MonthDay.from(date).equals(MonthDay.from(today))).isPresent())
                    .map(SpexDto::id)
                    .collect(Collectors.toSet());
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            log.debug("Could not read the premieres from Spexipedia", e);
            return Set.of();
        }
    }

    private static Optional<LocalDate> premiereOf(final SpexDto spex) {
        try {
            return Optional.ofNullable(spex.premiere()).map(LocalDate::parse);
        } catch (final DateTimeParseException _) {
            return Optional.empty();
        }
    }

    /**
     * EDITOR. The numbers of a spex in Spexipedia, for linking tracks.
     */
    public List<NumberDto> numbers(final UUID spexId) {
        callerService.current().requireEditor();
        try {
            return client.numbers(spexId);
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            throw new SpexipediaUnavailableException(e);
        }
    }

    public NumberDto number(final UUID id) {
        try {
            return client.number(id).orElseThrow(() -> new nu.fgv.spexify.server.util.error.BadRequestException("error.number.notFound", id));
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            throw new SpexipediaUnavailableException(e);
        }
    }

    public void copy(final SpexDto spex, final SpexRef ref) {
        ref.setId(spex.id());
        ref.setParentId(spex.parentId());
        ref.setTitle(spex.title());
        ref.setYear(spex.year());
        ref.setRevival(spex.revival());
        ref.setCategoryId(spex.categoryId());
        ref.setCategoryName(spex.categoryName());
        ref.setRefreshedAt(clock.instant());
    }

    /**
     * Refreshes the copied spex data of every album, at night.
     */
    @Transactional
    public void refresh() {
        final List<Album> albums = albumRepository.findWithSpex();

        if (albums.isEmpty()) {
            return;
        }
        try {
            final Map<UUID, SpexDto> spex = client.allSpex().stream().collect(Collectors.toMap(SpexDto::id, Function.identity(), (a, _) -> a));

            for (final Album album : albums) {
                for (final SpexRef ref : album.getSpex()) {
                    final SpexDto current = spex.get(ref.getId());

                    if (current != null) {
                        copy(current, ref);
                    } else if (StringUtils.hasText(ref.getTitle())) {
                        log.warn("Spex {} of album {} is no longer in Spexipedia", ref.getId(), album.getId());
                    }
                }
            }
        } catch (final SpexipediaClient.SpexipediaCallException e) {
            log.warn("Could not refresh spex from Spexipedia: {}", e.getMessage());
        }
    }
}
