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

import nu.fgv.spexify.server.TestClock;
import nu.fgv.spexify.server.catalog.TrackRepository;
import nu.fgv.spexify.server.config.SpexifyProperties;
import nu.fgv.spexify.server.spex.SpexipediaClient.CreditNode;
import nu.fgv.spexify.server.spex.SpexipediaClient.CreditValue;
import nu.fgv.spexify.server.spex.SpexipediaClient.KindNode;
import nu.fgv.spexify.server.spex.SpexipediaClient.NumberCreditsNode;
import nu.fgv.spexify.server.spex.SpexipediaClient.PersonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NumberCreditsTest {

    private static final String LYRICS = "01900000-0000-7000-8000-000000000201";
    private static final String ARRANGEMENT = "01900000-0000-7000-8000-000000000203";

    private final TestClock clock = new TestClock(Instant.parse("2026-11-19T10:00:00Z"));

    @Mock
    private SpexipediaClient client;

    @Mock
    private TrackRepository trackRepository;

    private NumberCredits credits;

    private static NumberCreditsNode number(final long id) {
        final KindNode lyrics = new KindNode(LYRICS, "Text", "Lyrics", 1);
        final KindNode arrangement = new KindNode(ARRANGEMENT, "Arrangemang", "Arrangement", 3);
        final KindNode choreography = new KindNode("01900000-0000-7000-8000-000000000204", "Koreografi", "Choreography", 4);

        return new NumberCreditsNode(uuid(id).toString(), "Dancing Queen", "ABBA", List.of(
                new CreditNode(arrangement, 0, new CreditValue(null, "Gästarrangören")),
                new CreditNode(lyrics, 1, new CreditValue(new PersonNode(uuid(901).toString(), "Anna Andersson", "Ankan"), null)),
                new CreditNode(lyrics, 0, new CreditValue(new PersonNode(uuid(902).toString(), null, "Bosse"), null)),
                // An unpublished spexare comes without a name
                new CreditNode(lyrics, 2, new CreditValue(new PersonNode(null, null, null), null)),
                new CreditNode(choreography, 0, new CreditValue(null, "Koreografen"))));
    }

    @BeforeEach
    void setUp() {
        final SpexifyProperties properties = mock(SpexifyProperties.class);

        when(properties.spexipedia()).thenReturn(new SpexifyProperties.Spexipedia("http://spexipedia", "", true, LYRICS, ARRANGEMENT));
        credits = new NumberCredits(client, trackRepository, clock, properties);
    }

    @Test
    void creditsComeInSpexipediasOrderWithNamesAndTheMelodyFromTheOriginalSong() {
        when(trackRepository.findLinkedNumberIds()).thenReturn(List.of(uuid(1)));
        when(client.numberCredits(anyList())).thenReturn(List.of(number(1)));

        final NumberCredits.Info info = credits.of(uuid(1));

        assertThat(info).isNotNull();
        assertThat(info.melody()).isEqualTo("Dancing Queen (ABBA)");
        assertThat(info.credits()).extracting(NumberCredits.Credit::name)
                .containsExactly("Bosse", "Anna Andersson", "Gästarrangören", "Koreografen");
        assertThat(credits.lyricsAuthors(info)).containsExactly("Bosse", "Anna Andersson");
        assertThat(credits.arrangers(info)).containsExactly("Gästarrangören");
        assertThat(info.credits().getFirst().spexareId()).isEqualTo(uuid(902));
    }

    @Test
    void everyLinkedNumberIsAskedForAtOnceAndKeptForTenMinutes() {
        when(trackRepository.findLinkedNumberIds()).thenReturn(List.of(uuid(1), uuid(2)));
        when(client.numberCredits(anyList())).thenReturn(List.of(number(1), number(2)));

        assertThat(credits.of(uuid(1))).isNotNull();
        assertThat(credits.of(uuid(2))).isNotNull();
        verify(client, times(1)).numberCredits(anyList());

        clock.advance(Duration.ofMinutes(11));
        assertThat(credits.of(uuid(2))).isNotNull();
        verify(client, times(2)).numberCredits(anyList());
    }

    @Test
    void withoutSpexipediaTheLastAnswerIsUsedUntilItIsTooOld() {
        when(trackRepository.findLinkedNumberIds()).thenReturn(List.of(uuid(1)));
        when(client.numberCredits(anyList())).thenReturn(List.of(number(1)))
                .thenThrow(new SpexipediaClient.SpexipediaCallException("down"));

        assertThat(credits.of(uuid(1))).isNotNull();
        // A number not asked for before makes it ask again, which fails, and the earlier answer is kept
        assertThat(credits.of(List.of(uuid(1), uuid(3)))).containsOnlyKeys(uuid(1));

        clock.advance(Duration.ofMinutes(11));
        assertThat(credits.of(uuid(1))).isNull();
    }
}
