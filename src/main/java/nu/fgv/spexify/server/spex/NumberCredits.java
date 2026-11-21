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
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.config.SpexifyProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The credits and the original song of Spexipedia's numbers, which a track that is a recording of a number shows
 * instead of credits of its own (fgv-infra-deploy docs/data-ownership.md). They are Spexipedia's and are never stored:
 * the numbers that tracks are linked to are asked for together and kept in memory for at most ten minutes, like
 * Spexipedia keeps Spexregister's names. Every number a track is linked to is asked for at once, so that a list of
 * tracks costs one question. When Spexipedia is unavailable, the last answer is used until it is too old,
 * and then the tracks show no credits.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class NumberCredits {

    static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final SpexipediaClient client;
    private final TrackRepository trackRepository;
    private final Clock clock;
    private final @Nullable UUID lyricsKind;
    private final @Nullable UUID arrangementKind;
    private Map<UUID, Info> numbers = Map.of();
    private Set<UUID> asked = Set.of();
    private Instant fetchedAt = Instant.EPOCH;

    public NumberCredits(final SpexipediaClient client, final TrackRepository trackRepository, final Clock clock,
                         final SpexifyProperties properties) {
        this.client = client;
        this.trackRepository = trackRepository;
        this.clock = clock;
        this.lyricsKind = uuid(properties.spexipedia() == null ? null : properties.spexipedia().lyricsCreditKind());
        this.arrangementKind = uuid(properties.spexipedia() == null ? null : properties.spexipedia().arrangementCreditKind());
    }

    private static @Nullable UUID uuid(final @Nullable String value) {
        try {
            return StringUtils.hasText(value) ? UUID.fromString(value.strip()) : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    static @Nullable String melody(final @Nullable String title, final @Nullable String artist) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return StringUtils.hasText(artist) ? title.strip() + " (" + artist.strip() + ")" : title.strip();
    }

    static Info toInfo(final SpexipediaClient.NumberCreditsNode node) {
        final List<Credit> credits = node.credits() == null ? List.of() : node.credits().stream()
                .filter(Objects::nonNull)
                .map(NumberCredits::toCredit)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Credit::kindPosition).thenComparingInt(Credit::position))
                .toList();

        return new Info(melody(node.originalTitle(), node.originalArtist()), credits);
    }

    private static @Nullable Credit toCredit(final SpexipediaClient.CreditNode node) {
        final SpexipediaClient.PersonNode person = node.credit() == null ? null : node.credit().person();
        final String name = person != null
                ? (StringUtils.hasText(person.name()) ? person.name() : person.nickname())
                : node.credit() == null ? null : node.credit().name();

        // An unpublished spexare comes without a name and is left out, as on Spexipedia's pages
        if (!StringUtils.hasText(name) || node.kind() == null) {
            return null;
        }
        return new Credit(UUID.fromString(node.kind().id()), node.kind().nameSv(), node.kind().nameEn(), node.kind().position(),
                node.position(), person == null || person.id() == null ? null : UUID.fromString(person.id()), name.strip());
    }

    /**
     * The numbers' credits, for the given numbers and every other number already asked for; numbers Spexipedia does
     * not know, or that could not be asked for, are left out.
     */
    public synchronized Map<UUID, Info> of(final Collection<UUID> numberIds) {
        final Instant now = clock.instant();
        final boolean old = Duration.between(fetchedAt, now).compareTo(MAX_AGE) > 0;
        final boolean missing = !asked.containsAll(numberIds);

        if (old || missing) {
            final Set<UUID> wanted = new HashSet<>(trackRepository.findLinkedNumberIds());

            wanted.addAll(numberIds);
            try {
                final Map<UUID, Info> fetched = new HashMap<>();

                for (final SpexipediaClient.NumberCreditsNode node : client.numberCredits(List.copyOf(wanted))) {
                    fetched.put(UUID.fromString(node.id()), toInfo(node));
                }
                numbers = Map.copyOf(fetched);
                asked = Set.copyOf(wanted);
                fetchedAt = now;
            } catch (final RuntimeException e) {
                log.warn("Could not ask Spexipedia for the credits of {} numbers: {}", wanted.size(), e.getMessage());
                if (old) {
                    numbers = Map.of();
                    asked = Set.of();
                    fetchedAt = Instant.EPOCH;
                }
            }
        }
        final Map<UUID, Info> result = new HashMap<>();

        numberIds.forEach(id -> {
            final Info info = numbers.get(id);

            if (info != null) {
                result.put(id, info);
            }
        });
        return result;
    }

    public @Nullable Info of(final @Nullable UUID numberId) {
        return numberId == null ? null : of(List.of(numberId)).get(numberId);
    }

    /**
     * The names credited with the kind that is a track's lyrics author.
     */
    public List<String> lyricsAuthors(final @Nullable Info info) {
        return names(info, lyricsKind);
    }

    /**
     * The names credited with the kind that is a track's arranger.
     */
    public List<String> arrangers(final @Nullable Info info) {
        return names(info, arrangementKind);
    }

    private static List<String> names(final @Nullable Info info, final @Nullable UUID kind) {
        if (info == null || kind == null) {
            return List.of();
        }
        return info.credits().stream().filter(credit -> kind.equals(credit.kindId())).map(Credit::name).distinct().toList();
    }

    /**
     * A number's original song as a track's melody ("Dancing Queen (ABBA)") and its credits, in Spexipedia's order.
     */
    public record Info(@Nullable String melody, List<Credit> credits) {
    }

    /**
     * One credit of a number: its kind, and the spexare (as Spexipedia shows them to a logged-in user) or the name of
     * someone outside FGV.
     */
    public record Credit(UUID kindId, String kindNameSv, String kindNameEn, int kindPosition, int position,
                         @Nullable UUID spexareId, String name) {
    }
}
