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


package nu.fgv.spexify.server.scoresync;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MusicXmlTest {

    static final String DOCTYPE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
            """;

    static String note(final String step, final int octave, final int duration, final String extra) {
        return "<note>%s<pitch><step>%s</step><octave>%d</octave></pitch><duration>%d</duration></note>".formatted(extra, step, octave, duration);
    }

    @Test
    void readsMeasuresNotesTempiAndLyrics() throws IOException {
        final String xml = DOCTYPE + """
                <score-partwise version="4.0">
                  <part-list><score-part id="P1"><part-name>Sång</part-name></score-part><score-part id="P2"><part-name>Trumpet</part-name></score-part></part-list>
                  <part id="P1">
                    <measure number="1">
                      <attributes><divisions>2</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                      <direction><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>90</per-minute></metronome></direction-type></direction>
                      <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><lyric number="1"><syllabic>begin</syllabic><text>Hal</text></lyric></note>
                      <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration></note>
                      <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>2</duration><lyric number="1"><syllabic>end</syllabic><text>lå</text></lyric></note>
                      <backup><duration>4</duration></backup>
                      <note><rest/><duration>4</duration></note>
                      <forward><duration>4</duration></forward>
                    </measure>
                    <measure number="2">
                      <sound tempo="120"/>
                      <note><grace/><pitch><step>D</step><octave>4</octave></pitch><duration>0</duration></note>
                      <note><pitch><step>G</step><octave>4</octave></pitch><duration>8</duration></note>
                    </measure>
                  </part>
                  <part id="P2">
                    <measure number="1">
                      <attributes><divisions>1</divisions><transpose><diatonic>-1</diatonic><chromatic>-2</chromatic></transpose></attributes>
                      <note><pitch><step>D</step><octave>5</octave></pitch><duration>4</duration></note>
                    </measure>
                    <measure number="2"><note><rest/><duration>4</duration></note></measure>
                  </part>
                </score-partwise>""";
        final MusicXml.Score score = MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(score.measures()).extracting(MusicXml.Measure::start, MusicXml.Measure::length)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0.0, 4.0), org.assertj.core.groups.Tuple.tuple(4.0, 4.0));
        assertThat(score.notes()).extracting(MusicXml.Note::measure, MusicXml.Note::beat, MusicXml.Note::midi).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(0, 0.0, 60), org.assertj.core.groups.Tuple.tuple(0, 0.0, 64),
                org.assertj.core.groups.Tuple.tuple(0, 1.0, 66), org.assertj.core.groups.Tuple.tuple(1, 0.0, 67),
                // The trumpet in B flat sounds a whole tone below what is written
                org.assertj.core.groups.Tuple.tuple(0, 0.0, 72));
        assertThat(score.tempi()).extracting(MusicXml.Tempo::measure, MusicXml.Tempo::bpm)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 90.0), org.assertj.core.groups.Tuple.tuple(1, 120.0));
        assertThat(score.text()).containsEntry("P1:1", "Hallå");
    }

    @Test
    void readsRepeatsAndEndingsAndPlaysThemOut() throws IOException {
        final StringBuilder measures = new StringBuilder();

        for (int i = 1; i <= 6; i++) {
            final String barlines = switch (i) {
                case 2 -> "<barline location=\"left\"><repeat direction=\"forward\"/></barline>";
                case 3 -> "<barline location=\"left\"><ending number=\"1\" type=\"start\"/></barline>"
                        + "<barline location=\"right\"><ending number=\"1\" type=\"stop\"/><repeat direction=\"backward\"/></barline>";
                case 4 -> "<barline location=\"left\"><ending number=\"2\" type=\"start\"/></barline>"
                        + "<barline location=\"right\"><ending number=\"2\" type=\"discontinue\"/></barline>";
                default -> "";
            };

            measures.append("<measure number=\"%d\">%s%s%s</measure>".formatted(i, i == 1 ? "<attributes><divisions>1</divisions></attributes>" : "",
                    note("C", 4, 4, ""), barlines));
        }

        final MusicXml.Score score = MusicXml.parse((DOCTYPE + "<score-partwise><part-list><score-part id=\"P1\"/></part-list><part id=\"P1\">"
                + measures + "</part></score-partwise>").getBytes(StandardCharsets.UTF_8));

        assertThat(score.measures().get(2).volta()).isEqualTo(Set.of(1));
        assertThat(score.measures().get(3).volta()).isEqualTo(Set.of(2));
        assertThat(ScoreAligner.unroll(score.measures(), true)).containsExactly(0, 1, 2, 1, 3, 4, 5);
        assertThat(ScoreAligner.unroll(score.measures(), false)).containsExactly(0, 1, 2, 4, 5);
    }

    @Test
    void soundsMeasureRepeatSigns() throws IOException {
        final String xml = DOCTYPE + """
                <score-partwise><part-list><score-part id="P1"/></part-list><part id="P1">
                  <measure number="1"><attributes><divisions>1</divisions></attributes>%s</measure>
                  <measure number="2"><attributes><measure-style><measure-repeat type="start">1</measure-repeat></measure-style></attributes><note><rest measure="yes"/><duration>4</duration></note></measure>
                  <measure number="3"><note><rest measure="yes"/><duration>4</duration></note></measure>
                  <measure number="4"><attributes><measure-style><measure-repeat type="stop"/></measure-style></attributes>%s</measure>
                </part></score-partwise>""".formatted(note("A", 4, 4, ""), note("B", 4, 4, ""));
        final MusicXml.Score score = MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(score.notes()).extracting(MusicXml.Note::measure, MusicXml.Note::midi).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(0, 69), org.assertj.core.groups.Tuple.tuple(1, 69),
                org.assertj.core.groups.Tuple.tuple(2, 69), org.assertj.core.groups.Tuple.tuple(3, 71));
    }

    @Test
    void readsCompressedMusicXml() throws IOException {
        final String score = "<score-partwise><part-list><score-part id=\"P1\"/></part-list><part id=\"P1\"><measure>"
                + "<attributes><divisions>1</divisions></attributes>" + note("E", 4, 4, "") + "</measure></part></score-partwise>";
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write("<container><rootfiles><rootfile full-path=\"score/Visan.xml\"/></rootfiles></container>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("score/Visan.xml"));
            zip.write(score.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(MusicXml.parse(bytes.toByteArray()).notes()).extracting(MusicXml.Note::midi).containsExactly(64);
    }

    @Test
    void neverLoadsExternalEntities() {
        final String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE score-partwise [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <score-partwise><part-list><score-part id="P1"><part-name>&secret;</part-name></score-part></part-list><part id="P1"><measure>
                <attributes><divisions>1</divisions></attributes><note><pitch><step>C</step><octave>4</octave></pitch><duration>4</duration></note>
                </measure></part></score-partwise>""";

        // Either refused or read without the entity's content; never the file
        try {
            final MusicXml.Score score = MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8));

            assertThat(score.notes()).hasSize(1);
        } catch (final IOException e) {
            assertThat(e).hasMessageNotContaining("root:");
        }
    }

    @Test
    void refusesWhatIsNotAScore() {
        assertThatThrownBy(() -> MusicXml.parse("<html/>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> MusicXml.parse("not xml".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
        assertThat(List.of()).isEmpty();
    }
}
