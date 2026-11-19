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


package nu.fgv.spexify.server.lyrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SingerLabelsTest {

    private static final List<String> CHARACTERS = List.of("Philip", "Pamela", "Cremona", "Cremona Fix", "Syster Emma", "Pesten (Pe)");

    @Test
    void findsLabelsAtTheStartOfLines() {
        assertThat(SingerLabels.labels("""
                [00:01.00]Ph: Här står jag
                [00:03.00] Ph/Pa: och här står vi
                KÖR: Alla tillsammans
                Ingen etikett: här, men en rad
                En rad utan kolon""")).containsExactly("Ph", "Ph/Pa", "KÖR", "Ingen etikett");
    }

    @Test
    void resolvesLabelsToCharactersAndNeverGuesses() {
        final var names = SingerLabels.resolve("""
                Ph: a
                Ph/Pa: b
                PhPa: c
                SE: d
                Cr: e
                Pe: f
                P: g
                Kör: h
                Okänd: i""", CHARACTERS);

        assertThat(names)
                .containsEntry("Ph", "Philip")
                .containsEntry("Ph/Pa", "Philip & Pamela")
                .containsEntry("PhPa", "Philip & Pamela")
                .containsEntry("SE", "Syster")
                .containsEntry("Cr", "Cremona")
                .containsEntry("Pe", "Pesten")
                .containsEntry("Kör", "Kör")
                // P fits Philip, Pamela and Pesten alike; Okänd fits no one
                .doesNotContainKeys("P", "Okänd");
    }

    @Test
    void namesPeopleWhoShareAFirstNameInFull() {
        final var people = SingerLabels.people(List.of("Kalle Anka", "Kalle Blomkvist"));

        assertThat(people).extracting(SingerLabels.Person::display).containsExactly("Kalle Anka", "Kalle Blomkvist");
    }
}
