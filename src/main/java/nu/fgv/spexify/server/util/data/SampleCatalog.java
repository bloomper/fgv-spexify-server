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

package nu.fgv.spexify.server.util.data;

import java.util.List;

/**
 * Made-up song titles, performers, melodies and lyrics for the sample albums. Swedish characters on purpose.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class SampleCatalog {

    static final List<String> TITLES = List.of(
            "Ouvertyr", "Öppningskören", "Kärleksduetten", "Hovets klagan", "Rövarvisan", "Ödets nummer",
            "Tronföljarens tango", "Spöket på slottet", "Bröllopsmarschen", "Älskade fiende", "Krigsrådet",
            "Månskensserenaden", "Gycklarnas gavott", "Förvecklingen", "Avslöjandet", "Finalen", "Extranumret");

    static final List<String> PERFORMERS = List.of(
            "Kungen, Drottningen", "Hjälten", "Skurken och hans hantlangare", "Hela ensemblen", "Prinsessan",
            "Hovnarren", "Kören", "Berättaren, Hjälten", "Damkören", "Herrkören");

    static final List<String> MELODIES = List.of(
            "Dancing Queen (ABBA)", "Bohemian Rhapsody (Queen)", "Sommartider (Gyllene Tider)",
            "Hallelujah (Leonard Cohen)", "I Will Survive (Gloria Gaynor)", "Vem kan segla förutan vind (trad.)",
            "Don't Stop Me Now (Queen)", "Dansa i neon (Lena Philipsson)");

    static final List<String> LYRICS_AUTHORS = List.of("Textgruppen", "Manusgruppen", "A. Författare", "B. Rimsmed");

    static final String PLAIN_LYRICS = """
            Här står vi nu på scenen
            med kronor och med svärd,
            och ingen i publiken
            vet vad som komma skall.""";

    static final String SYNCED_LYRICS = """
            [00:00.50]Här står vi nu på scenen
            [00:02.50]med kronor och med svärd,
            [00:04.50]och ingen i publiken
            [00:06.50]vet vad som komma skall.""";

    static final String COMPILATION_TITLE = "Spexjubileum – de bästa numren";

    private SampleCatalog() {
    }
}
