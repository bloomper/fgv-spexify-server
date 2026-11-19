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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoreAlignerTest {

    /**
     * A melody of four quarter notes a measure over a bass note, never the same measure twice, so that the alignment
     * has something to hold on to.
     */
    private static final int[][] MELODY = new int[24][];

    static {
        final Random random = new Random(42);
        final int[] scale = {60, 62, 64, 65, 67, 69, 71, 72, 74, 76};

        for (int m = 0; m < MELODY.length; m++) {
            MELODY[m] = new int[]{scale[random.nextInt(scale.length)], scale[random.nextInt(scale.length)],
                    scale[random.nextInt(scale.length)], scale[random.nextInt(scale.length)], 36 + random.nextInt(12)};
        }
    }

    private static String pitch(final int midi) {
        final String[] steps = {"C", "C", "D", "D", "E", "F", "F", "G", "G", "A", "A", "B"};
        final int[] alters = {0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0};

        return "<pitch><step>%s</step>%s<octave>%d</octave></pitch>".formatted(steps[midi % 12],
                alters[midi % 12] == 0 ? "" : "<alter>1</alter>", midi / 12 - 1);
    }

    private static MusicXml.Score score(final boolean repeat) throws IOException {
        final StringBuilder xml = new StringBuilder(MusicXmlTest.DOCTYPE)
                .append("<score-partwise><part-list><score-part id=\"P1\"/></part-list><part id=\"P1\">");

        for (int m = 0; m < MELODY.length; m++) {
            xml.append("<measure>");
            if (m == 0) {
                xml.append("<attributes><divisions>1</divisions></attributes><sound tempo=\"120\"/>");
            }
            if (repeat && m == 8) {
                xml.append("<barline location=\"left\"><repeat direction=\"forward\"/></barline>");
            }
            for (int b = 0; b < 4; b++) {
                xml.append("<note>").append(pitch(MELODY[m][b])).append("<duration>1</duration></note>");
            }
            xml.append("<backup><duration>4</duration></backup><note>").append(pitch(MELODY[m][4])).append("<duration>4</duration></note>");
            if (repeat && m == 15) {
                xml.append("<barline location=\"right\"><repeat direction=\"backward\"/></barline>");
            }
            xml.append("</measure>");
        }
        return MusicXml.parse(xml.append("</part></score-partwise>").toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Plays the measures in the given order at the given tempo, with harmonics and a little noise, after a lead-in.
     */
    private static float[] perform(final int[] order, final double bpm, final double leadIn) {
        final double beat = 60.0 / bpm;
        final int rate = ScoreAligner.SAMPLE_RATE;
        final float[] samples = new float[(int) ((leadIn + order.length * 4 * beat + 2) * rate)];
        final Random noise = new Random(7);

        for (int k = 0; k < order.length; k++) {
            final int[] measure = MELODY[order[k]];
            final double start = leadIn + k * 4 * beat;

            for (int b = 0; b < 4; b++) {
                add(samples, measure[b], start + b * beat, beat * 0.95, 0.3);
            }
            add(samples, measure[4], start, 4 * beat * 0.98, 0.2);
        }
        for (int i = 0; i < samples.length; i++) {
            samples[i] += (float) (noise.nextGaussian() * 0.01);
        }
        return samples;
    }

    private static void add(final float[] samples, final int midi, final double start, final double length, final double gain) {
        final double f = 440 * Math.pow(2, (midi - 69) / 12.0);
        final int from = (int) (start * ScoreAligner.SAMPLE_RATE);
        final int to = Math.min(samples.length, (int) ((start + length) * ScoreAligner.SAMPLE_RATE));

        for (int i = from; i < to; i++) {
            final double t = (i - from) / (double) ScoreAligner.SAMPLE_RATE;
            final double envelope = Math.exp(-2.5 * t) * Math.min(1, t * 200);
            double value = 0;

            for (int h = 1; h <= 3; h++) {
                if (f * h < ScoreAligner.SAMPLE_RATE / 2.0) {
                    value += Math.sin(2 * Math.PI * f * h * t) / h;
                }
            }
            samples[i] += (float) (gain * envelope * value);
        }
    }

    /**
     * The measure played at the given second of the performance, in written order.
     */
    private static int expected(final int[] order, final double bpm, final double leadIn, final double seconds) {
        final int k = (int) Math.floor((seconds - leadIn) / (4 * 60.0 / bpm));

        return k < 0 || k >= order.length ? -1 : order[k];
    }

    @Test
    void followsASlowerPerformanceWithALeadIn() throws IOException {
        final MusicXml.Score score = score(false);
        final int[] order = ScoreAligner.unroll(score.measures(), true);
        final ScoreAligner.Result result = ScoreAligner.align(score, perform(order, 96, 3), null);
        int right = 0;

        for (final ScoreAligner.SyncPoint point : result.points()) {
            final int measure = expected(order, 96, 3, point.seconds());

            right += Math.abs(point.measure() - measure) <= 1 ? 1 : 0;
        }
        assertThat(result.points()).hasSizeGreaterThan(40);
        assertThat((double) right / result.points().size()).isGreaterThan(0.9);
        assertThat(result.quality()).isGreaterThan(0.3);
        assertThat(result.shift()).isZero();
        assertThat(result.tuning()).isCloseTo(0.0, within(0.11));
        // Seconds strictly increase, as the web needs them
        for (int i = 1; i < result.points().size(); i++) {
            assertThat(result.points().get(i).seconds()).isGreaterThan(result.points().get(i - 1).seconds());
        }
    }

    @Test
    void followsTheRepeat() throws IOException {
        final MusicXml.Score score = score(true);
        final int[] order = ScoreAligner.unroll(score.measures(), true);

        assertThat(order).hasSize(32);

        final ScoreAligner.Result result = ScoreAligner.align(score, perform(order, 120, 1), null);
        final List<Integer> measures = new ArrayList<>();
        int right = 0;

        for (final ScoreAligner.SyncPoint point : result.points()) {
            measures.add(point.measure());
            right += Math.abs(point.measure() - expected(order, 120, 1, point.seconds())) <= 1 ? 1 : 0;
        }
        assertThat((double) right / result.points().size()).isGreaterThan(0.85);
        // The cursor goes back to the start of the repeat once
        assertThat(java.util.stream.IntStream.range(1, measures.size()).filter(i -> measures.get(i) < measures.get(i - 1) - 4).count()).isEqualTo(1);
    }

    @Test
    void readsLyricLinesAndAnchorsThem() throws IOException {
        assertThat(ScoreAligner.lrc("[00:01.50]Här står vi\n[01:02]  \n[01:03.25] på scenen"))
                .extracting(ScoreAligner.LyricLine::seconds).containsExactly(1.5, 63.25);
        assertThat(ScoreAligner.normalise("Å, vad Skönt!")).isEqualTo("åvadskönt");
    }

    @Test
    void matchesLikePythonsDifflib() {
        assertThat(new SequenceMatcher("abcd", "bcde").ratio()).isEqualTo(0.75);
        assertThat(new SequenceMatcher("härstårvi", "härstårvipåscenen").matchingBlocks()).hasSize(1);
        assertThat(new SequenceMatcher("", "").ratio()).isEqualTo(1.0);
    }

    @Test
    void transformsLikeAnFft() {
        final double[] re = new double[8];
        final double[] im = new double[8];

        re[1] = 1;
        ScoreAligner.fft(re, im);
        for (int k = 0; k < 8; k++) {
            assertThat(Math.hypot(re[k], im[k])).isCloseTo(1.0, within(1e-9));
        }
    }
}
