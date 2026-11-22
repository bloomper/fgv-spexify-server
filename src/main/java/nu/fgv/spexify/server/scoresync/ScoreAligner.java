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

import nu.fgv.spexify.server.scoresync.MusicXml.Measure;
import nu.fgv.spexify.server.scoresync.MusicXml.Note;
import nu.fgv.spexify.server.scoresync.MusicXml.Score;
import nu.fgv.spexify.server.scoresync.MusicXml.Syllable;
import nu.fgv.spexify.server.scoresync.MusicXml.Tempo;
import nu.fgv.spexify.server.util.StoredText;
import nu.fgv.spexify.server.util.TextException;
import org.jspecify.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where in a score each moment of a recording is, so that the web's cursor can follow the sheet music while the
 * recording plays. A port of the old Spexify's {@code score_align.py}, method "dtw+lyrics":
 *
 * <ol>
 *     <li>lyric anchors: the start times of synchronised lyric lines matched, by their text, to the score's syllables;</li>
 *     <li>dynamic time warping of the recording's chroma against chroma made from the score's notes (repeats played
 *     out), with the anchors as a soft corridor, tried with and without the repeats and in the two likeliest
 *     transpositions;</li>
 *     <li>a quality from 0 to 1: how much better the path matches than the matrix on average, how sane its tempo is,
 *     and how well it agrees with the anchors.</li>
 * </ol>
 *
 * <p>Pure: no files, no database. The recording comes as mono samples at {@link #SAMPLE_RATE}.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ScoreAligner {

    /**
     * Samples per second of the recording it is given.
     */
    public static final int SAMPLE_RATE = 10_000;
    /**
     * Seconds per frame, on both axes.
     */
    static final double HOP = 0.1;
    /**
     * The longest recording it aligns, in seconds.
     */
    public static final int MAX_SECONDS = 900;
    private static final int NFFT = 4096;
    private static final double SPLIT = 1.0;
    private static final long MAX_CELLS = 30_000_000L;
    private static final double[][] HARMONICS = {{0, 1.0}, {0, 0.6}, {7, 0.45}, {0, 0.35}, {4, 0.25}, {7, 0.2}};
    private static final int[][] STEPS = {{1, 1}, {1, 2}, {2, 1}, {1, 3}, {3, 1}};
    private static final float[] PENALTIES = {0f, 0.12f, 0.12f, 0.30f, 0.30f};
    private static final double FREE = 0.4;
    private static final float INF = 1e9f;
    private static final Pattern LRC_LINE = Pattern.compile("\\s*\\[(\\d+):(\\d+(?:\\.\\d+)?)]\\s*(.*)");
    private static final Pattern CHORD_PREFIX = Pattern.compile("^[^:\\s]{1,8}:(?=\\S)");
    private static final Pattern NOT_LETTER = Pattern.compile("[^a-z0-9åäöé]");

    private ScoreAligner() {
    }

    // ------------------------------------------------------------------------------------------------ the score

    /**
     * The order in which written measures are played: with the repeats and numbered endings, or, without repeats,
     * every measure once and only the first endings.
     */
    static int[] unroll(final List<Measure> measures, final boolean repeats) {
        final int n = measures.size();

        if (!repeats) {
            final int[] once = java.util.stream.IntStream.range(0, n)
                    .filter(i -> measures.get(i).volta().isEmpty() || measures.get(i).volta().contains(1)).toArray();

            return once.length == 0 ? java.util.stream.IntStream.range(0, n).toArray() : once;
        }

        final List<Integer> out = new ArrayList<>();
        final Map<Integer, Integer> done = new HashMap<>();
        int i = 0;
        int start = 0;
        int pass = 1;
        int guard = 0;
        Set<Integer> previous = null;

        while (i < n && guard < 20 * n + 100) {
            guard++;

            final Measure m = measures.get(i);
            final Set<Integer> volta = m.volta();

            if (!volta.isEmpty()) {
                if (previous != null && !volta.equals(previous) && min(volta) <= min(previous)) {
                    pass = 1;
                }
                previous = volta;
            } else if (previous != null) {
                pass = 1;
                previous = null;
            }
            if (m.forward() && !(!out.isEmpty() && out.getLast() == i)) {
                start = pass == 1 || i >= start ? i : start;
            }
            if (!volta.isEmpty() && !volta.contains(pass)) {
                i++;
                continue;
            }
            out.add(i);
            if (m.backward()) {
                final int k = done.getOrDefault(i, 0);

                if (k < m.times() - 1) {
                    done.put(i, k + 1);
                    pass = !volta.isEmpty() ? pass + 1 : k + 2;
                    previous = null;
                    i = start;
                    continue;
                }
                pass = 1;
                start = i + 1;
            }
            i++;
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int min(final Set<Integer> values) {
        return values.stream().min(Integer::compare).orElse(0);
    }

    /**
     * The quarter note (played out) and nominal second at the start of every played measure and at every tempo mark.
     */
    static Knots knots(final Score score, final int[] order) {
        final Map<Integer, List<Tempo>> marks = new HashMap<>();

        score.tempi().forEach(tempo -> marks.computeIfAbsent(tempo.measure(), _ -> new ArrayList<>()).add(tempo));

        double bpm = 100;
        double q = 0;
        double s = 0;
        final List<Double> qs = new ArrayList<>();
        final List<Double> ss = new ArrayList<>();

        for (final int mi : order) {
            final double length = score.measures().get(mi).length();
            final List<double[]> cuts = new ArrayList<>();

            cuts.add(new double[]{0, bpm});
            marks.getOrDefault(mi, List.of()).stream()
                    .sorted(Comparator.comparingDouble(Tempo::beat).thenComparingDouble(Tempo::bpm))
                    .forEach(tempo -> cuts.add(new double[]{Math.min(Math.max(0, tempo.beat()), length), tempo.bpm()}));
            cuts.sort(Comparator.comparingDouble(cut -> cut[0]));

            final List<double[]> points = new ArrayList<>();

            for (final double[] cut : cuts) {
                if (!points.isEmpty() && Math.abs(points.getLast()[0] - cut[0]) < 1e-9) {
                    points.set(points.size() - 1, cut);
                } else {
                    points.add(cut);
                }
            }
            for (int k = 0; k < points.size(); k++) {
                final double b = points.get(k)[0];
                final double v = points.get(k)[1];
                final double next = Math.min(k + 1 < points.size() ? points.get(k + 1)[0] : length, length);

                qs.add(q + b);
                ss.add(s);
                s += Math.max(0, next - b) * 60.0 / v;
                bpm = v;
            }
            q += length;
        }
        qs.add(q);
        ss.add(s);
        return new Knots(qs.stream().mapToDouble(Double::doubleValue).toArray(), ss.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /**
     * Where each played measure starts, in quarter notes played out.
     */
    static double[] starts(final Score score, final int[] order) {
        final double[] starts = new double[order.length];
        double q = 0;

        for (int k = 0; k < order.length; k++) {
            starts[k] = q;
            q += score.measures().get(order[k]).length();
        }
        return starts;
    }

    /**
     * Chroma frames of the score at its nominal tempo, played out.
     */
    static double[][] scoreChroma(final Score score, final int[] order, final Knots knots) {
        final double[] starts = starts(score, order);
        final Map<Integer, List<Note>> byMeasure = new HashMap<>();

        score.notes().forEach(note -> byMeasure.computeIfAbsent(note.measure(), _ -> new ArrayList<>()).add(note));

        final int frames = (int) (knots.seconds()[knots.seconds().length - 1] / HOP) + 1;
        final double[][] chroma = new double[frames][12];

        for (int k = 0; k < order.length; k++) {
            for (final Note note : byMeasure.getOrDefault(order[k], List.of())) {
                final double t0 = interp(starts[k] + note.beat(), knots.quarters(), knots.seconds());
                final double t1 = interp(starts[k] + note.beat() + note.duration(), knots.quarters(), knots.seconds());
                final int f0 = (int) (t0 / HOP);
                final int f1 = Math.max(f0 + 1, (int) (t1 / HOP));

                for (final double[] harmonic : HARMONICS) {
                    final int pc = Math.floorMod(note.midi() + (int) harmonic[0], 12);

                    for (int f = Math.max(0, f0); f < Math.min(frames, f1); f++) {
                        chroma[f][pc] += harmonic[1];
                    }
                }
            }
        }
        for (final double[] frame : chroma) {
            for (int c = 0; c < 12; c++) {
                frame[c] = Math.log1p(3 * frame[c]);
            }
        }
        return chroma;
    }

    // ------------------------------------------------------------------------------------------------ the recording

    /**
     * Chroma frames of a mono recording at {@link #SAMPLE_RATE}, with its tuning estimated from spectral peaks.
     */
    static AudioChroma audioChroma(final float[] samples) {
        final int hop = (int) (SAMPLE_RATE * HOP);
        final float[] x = new float[samples.length + NFFT];

        System.arraycopy(samples, 0, x, NFFT / 2, samples.length);

        final int n = Math.max(0, (x.length - NFFT) / hop + 1);
        final double binHz = (double) SAMPLE_RATE / NFFT;
        final int lo = (int) Math.ceil(65.0 / binHz);
        final int hi = (int) Math.ceil(2100.0 / binHz);
        final int bins = hi - lo;
        final double[] fm = new double[bins];

        for (int b = 0; b < bins; b++) {
            fm[b] = 69 + 12 * (Math.log((lo + b) * binHz / 440.0) / Math.log(2));
        }

        final double[] window = new double[NFFT];

        for (int i = 0; i < NFFT; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (NFFT - 1));
        }

        final float[][] spec = new float[n][bins];
        final double[] re = new double[NFFT];
        final double[] im = new double[NFFT];
        final double[] row = new double[bins];

        for (int f = 0; f < n; f++) {
            for (int i = 0; i < NFFT; i++) {
                re[i] = x[f * hop + i] * window[i];
                im[i] = 0;
            }
            fft(re, im);

            double mean = 0;

            for (int b = 0; b < bins; b++) {
                row[b] = Math.hypot(re[lo + b], im[lo + b]);
                mean += row[b];
            }
            mean /= bins;
            for (int b = 0; b < bins; b++) {
                row[b] = Math.log1p(30 * row[b] / (mean + 1e-9));
            }

            final double median = median(row);

            for (int b = 0; b < bins; b++) {
                spec[f][b] = (float) Math.max(row[b] - median, 0);
            }
        }

        // Peaks only, and the tuning from where they fall between semitones
        final double[] histogram = new double[10];
        final float[][] weights = new float[n][bins];

        for (int f = 0; f < n; f++) {
            for (int b = 1; b < bins - 1; b++) {
                if (spec[f][b] > spec[f][b - 1] && spec[f][b] >= spec[f][b + 1]) {
                    weights[f][b] = spec[f][b];

                    final double fraction = fm[b] - Math.rint(fm[b]);

                    histogram[Math.min(9, (int) Math.floor((fraction + 0.5) * 10))] += spec[f][b];
                }
            }
        }

        int best = 0;

        for (int i = 1; i < 10; i++) {
            if (histogram[i] > histogram[best]) {
                best = i;
            }
        }

        final double tuning = (best + 0.5) / 10 - 0.5;
        final int[] pitchClass = new int[bins];

        for (int b = 0; b < bins; b++) {
            pitchClass[b] = Math.floorMod((int) Math.rint(fm[b] - tuning), 12);
        }

        final double[][] chroma = new double[n][12];

        for (int f = 0; f < n; f++) {
            for (int b = 0; b < bins; b++) {
                chroma[f][pitchClass[b]] += weights[f][b];
            }
        }
        return new AudioChroma(chroma, tuning);
    }

    /**
     * In-place radix-2 FFT; the length must be a power of two.
     */
    static void fft(final double[] re, final double[] im) {
        final int n = re.length;

        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;

            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];

                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            final double angle = -2 * Math.PI / length;
            final double wr = Math.cos(angle);
            final double wi = Math.sin(angle);

            for (int i = 0; i < n; i += length) {
                double cr = 1;
                double ci = 0;

                for (int k = 0; k < length / 2; k++) {
                    final int a = i + k;
                    final int b = a + length / 2;
                    final double tr = re[b] * cr - im[b] * ci;
                    final double ti = re[b] * ci + im[b] * cr;

                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;

                    final double next = cr * wr - ci * wi;

                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ matching

    private static double[][] smooth(final double[][] a) {
        final double[][] out = new double[a.length][12];

        for (int i = 0; i < a.length; i++) {
            for (int c = 0; c < 12; c++) {
                double sum = a[i][c];

                sum += i > 0 ? a[i - 1][c] : 0;
                sum += i + 1 < a.length ? a[i + 1][c] : 0;
                out[i][c] = sum / 3;
            }
        }
        return out;
    }

    /**
     * Mean-centred rows of unit length; a silent row stays zero and is reported as such.
     */
    private static boolean[] normalise(final double[][] a) {
        final boolean[] valid = new boolean[a.length];

        for (int i = 0; i < a.length; i++) {
            final double mean = Arrays.stream(a[i]).average().orElse(0);
            double norm = 0;

            for (int c = 0; c < 12; c++) {
                a[i][c] -= mean;
                norm += a[i][c] * a[i][c];
            }
            norm = Math.sqrt(norm);
            valid[i] = norm > 1e-6;
            for (int c = 0; c < 12; c++) {
                a[i][c] = valid[i] ? a[i][c] / norm : 0;
            }
        }
        return valid;
    }

    /**
     * The cosine distance of every recording frame to every score frame, the score transposed by {@code shift}.
     */
    static Matrix cost(final double[][] recording, final double[][] score, final int shift) {
        final double[][] a = smooth(recording);
        final double[][] rolled = new double[score.length][12];

        for (int j = 0; j < score.length; j++) {
            for (int c = 0; c < 12; c++) {
                rolled[j][Math.floorMod(c + shift, 12)] = score[j][c];
            }
        }

        final double[][] b = smooth(rolled);

        normalise(a);

        final boolean[] valid = normalise(b);
        final int n = a.length;
        final int m = b.length;
        final float[] cells = new float[n * m];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (!valid[j]) {
                    cells[i * m + j] = 1f;
                    continue;
                }

                double dot = 0;

                for (int c = 0; c < 12; c++) {
                    dot += a[i][c] * b[j][c];
                }
                cells[i * m + j] = (float) (1 - dot);
            }
        }
        return new Matrix(n, m, cells);
    }

    /**
     * The two likeliest transpositions of the score against the recording, by their long-term pitch-class profiles.
     */
    static int[] shifts(final double[][] recording, final double[][] score) {
        final double[] a = profile(recording);
        final double[] b = profile(score);
        final double[] scores = new double[12];
        final double norms = Math.sqrt(Arrays.stream(a).map(v -> v * v).sum()) * Math.sqrt(Arrays.stream(b).map(v -> v * v).sum()) + 1e-9;

        for (int s = 0; s < 12; s++) {
            double dot = 0;

            for (int c = 0; c < 12; c++) {
                dot += a[c] * b[Math.floorMod(c - s, 12)];
            }
            scores[s] = dot / norms;
        }

        final Integer[] order = java.util.stream.IntStream.range(0, 12).boxed().toArray(Integer[]::new);

        Arrays.sort(order, (x, y) -> Double.compare(scores[y], scores[x]));
        return new int[]{order[0], order[1]};
    }

    private static double[] profile(final double[][] frames) {
        final double[] mean = new double[12];

        for (final double[] frame : frames) {
            for (int c = 0; c < 12; c++) {
                mean[c] += frame[c] / Math.max(1, frames.length);
            }
        }

        final double average = Arrays.stream(mean).average().orElse(0);

        for (int c = 0; c < 12; c++) {
            mean[c] -= average;
        }
        return mean;
    }

    /**
     * Dynamic time warping, open at both ends of the recording: the score may start in its first 40 % and end in its
     * last 40 %, and the whole score is traversed.
     *
     * @return the path, as (recording frame, score frame) pairs
     */
    static List<int[]> dtw(final Matrix c) {
        final int n = c.rows();
        final int m = c.columns();
        final float[] d = new float[n * m];
        final byte[] back = new byte[n * m];
        final int free = Math.max(1, (int) (n * FREE));

        Arrays.fill(d, INF);
        for (int i = 0; i < Math.min(free, n); i++) {
            d[i * m] = c.at(i, 0);
            back[i * m] = -1;
        }

        final float[] best = new float[m];
        final byte[] arg = new byte[m];

        for (int i = 1; i < n; i++) {
            Arrays.fill(best, INF);
            Arrays.fill(arg, (byte) 0);
            for (int k = 0; k < STEPS.length; k++) {
                final int di = STEPS[k][0];
                final int dj = STEPS[k][1];

                if (i - di < 0 || dj >= m) {
                    continue;
                }

                final int from = (i - di) * m;

                for (int j = dj; j < m; j++) {
                    final float candidate = d[from + j - dj] + PENALTIES[k];

                    if (candidate < best[j]) {
                        best[j] = candidate;
                        arg[j] = (byte) k;
                    }
                }
            }
            for (int j = 0; j < m; j++) {
                d[i * m + j] = best[j] >= INF ? INF : best[j] + c.at(i, j);
                back[i * m + j] = arg[j];
            }
            if (i < free) {
                d[i * m] = c.at(i, 0);
                back[i * m] = -1;
            } else {
                d[i * m] = INF;
            }
        }

        final int endFrom = (int) (n * (1 - FREE));
        int i = Math.min(endFrom, n - 1);

        for (int candidate = i; candidate < n; candidate++) {
            if (d[candidate * m + m - 1] < d[i * m + m - 1]) {
                i = candidate;
            }
        }

        int j = m - 1;
        final List<int[]> path = new ArrayList<>();

        path.add(new int[]{i, j});
        while (true) {
            final int k = back[i * m + j];

            if (k < 0 || j == 0) {
                break;
            }
            i -= STEPS[k][0];
            j -= STEPS[k][1];
            if (i < 0 || j < 0) {
                break;
            }
            path.add(new int[]{i, j});
        }
        java.util.Collections.reverse(path);
        return path;
    }

    // ------------------------------------------------------------------------------------------------ lyrics

    /**
     * Lower case letters and digits, Swedish ones kept.
     */
    static String normalise(final @Nullable String text) {
        final String decomposed = Normalizer.normalize(text == null ? "" : text.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFKD);
        final StringBuilder kept = new StringBuilder();

        for (int i = 0; i < decomposed.length(); i++) {
            final char ch = decomposed.charAt(i);

            if (Character.getType(ch) != Character.NON_SPACING_MARK || ch == '̈' || ch == '̊') {
                kept.append(ch);
            }
        }
        return NOT_LETTER.matcher(Normalizer.normalize(kept, Normalizer.Form.NFC)).replaceAll("");
    }

    /**
     * The lines of synchronised lyrics with text: (seconds, text).
     */
    static List<LyricLine> lrc(final @Nullable String text) {
        final List<LyricLine> lines = new ArrayList<>();

        for (final String line : (text == null ? "" : text).split("\\R")) {
            final Matcher matcher = LRC_LINE.matcher(line);

            if (matcher.matches() && !normalise(matcher.group(3)).isEmpty()) {
                lines.add(new LyricLine(Integer.parseInt(matcher.group(1)) * 60 + Double.parseDouble(matcher.group(2)), matcher.group(3).strip()));
            }
        }
        return lines;
    }

    /**
     * Each lyric line of the score as one string of letters, with where each syllable starts in it.
     */
    static Map<String, Stream> streams(final Score score) {
        final Map<String, List<Syllable>> byStream = new LinkedHashMap<>();

        score.syllables().forEach(syllable -> byStream.computeIfAbsent(syllable.stream(), _ -> new ArrayList<>()).add(syllable));

        final Map<String, Stream> result = new LinkedHashMap<>();

        byStream.forEach((id, syllables) -> {
            final StringBuilder text = new StringBuilder();
            final List<double[]> index = new ArrayList<>();

            for (final Syllable syllable : syllables) {
                final String letters = normalise(CHORD_PREFIX.matcher(syllable.text().strip()).replaceFirst(""));

                if (letters.isEmpty()) {
                    continue;
                }
                index.add(new double[]{text.length(), syllable.measure(), syllable.beat()});
                text.append(letters);
            }
            if (text.length() >= 20) {
                result.put(id, new Stream(text.toString(), index));
            }
        });
        return result;
    }

    /**
     * For each lyric line, its likeliest places in the score's text (at most three), as (ratio, measure, beat).
     */
    static List<List<Candidate>> matchLines(final List<LyricLine> lines, final Map<String, Stream> streams) {
        final List<List<Candidate>> result = new ArrayList<>();

        for (final LyricLine line : lines) {
            final String l = normalise(line.text());
            final Map<String, Candidate> found = new LinkedHashMap<>();

            if (l.length() >= 5) {
                final Map<String, List<Integer>> trigrams = new HashMap<>();

                for (int a = 0; a + 3 <= l.length(); a++) {
                    trigrams.computeIfAbsent(l.substring(a, a + 3), _ -> new ArrayList<>()).add(a);
                }
                for (final Stream stream : streams.values()) {
                    final String buffer = stream.text();
                    final Map<Integer, Integer> votes = new LinkedHashMap<>();

                    for (int p = 0; p + 3 <= buffer.length(); p++) {
                        for (final int a : trigrams.getOrDefault(buffer.substring(p, p + 3), List.of())) {
                            votes.merge(Math.floorDiv(p - a, 4), 1, Integer::sum);
                        }
                    }

                    final List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(votes.entrySet());

                    ranked.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
                    for (final Map.Entry<Integer, Integer> vote : ranked.subList(0, Math.min(2, ranked.size()))) {
                        if (vote.getValue() < Math.max(3, l.length() / 4)) {
                            continue;
                        }

                        final int s0 = Math.max(0, vote.getKey() * 4 - 3);
                        final String segment = buffer.substring(Math.min(s0, buffer.length()), Math.min(buffer.length(), s0 + l.length() + 8));
                        final SequenceMatcher matcher = new SequenceMatcher(l, segment);
                        final double ratio = matcher.ratio();
                        final List<int[]> blocks = matcher.matchingBlocks().stream().filter(block -> block[2] >= 2).toList();

                        if (blocks.isEmpty() || ratio < 0.55) {
                            continue;
                        }

                        final int start = Math.max(0, s0 + blocks.getFirst()[1] - blocks.getFirst()[0]);
                        int j = 0;

                        while (j + 1 < stream.index().size() && stream.index().get(j + 1)[0] <= start) {
                            j++;
                        }

                        final double[] at = stream.index().get(j);
                        final String key = (int) at[1] + ":" + Math.round(at[2] * 100) / 100.0;
                        final Candidate previous = found.get(key);

                        if (previous == null || previous.ratio() < ratio) {
                            found.put(key, new Candidate(ratio, (int) at[1], at[2]));
                        }
                    }
                }
            }

            final List<Candidate> best = new ArrayList<>(found.values());

            best.sort((x, y) -> Double.compare(y.ratio(), x.ratio()));
            result.add(List.copyOf(best.subList(0, Math.min(3, best.size()))));
        }
        return result;
    }

    /**
     * The best chain of candidates that goes forward in both the recording and the score at a sane tempo.
     */
    static List<Anchor> anchors(final List<LyricLine> lines, final List<List<Candidate>> candidates, final Score score, final int[] order) {
        final double[] starts = starts(score, order);
        final Map<Integer, List<Integer>> plays = new HashMap<>();

        for (int k = 0; k < order.length; k++) {
            plays.computeIfAbsent(order[k], _ -> new ArrayList<>()).add(k);
        }

        final List<double[]> nodes = new ArrayList<>();

        for (int li = 0; li < candidates.size(); li++) {
            for (final Candidate candidate : candidates.get(li)) {
                for (final int k : plays.getOrDefault(candidate.measure(), List.of())) {
                    nodes.add(new double[]{li, candidate.ratio(), starts[k] + candidate.beat()});
                }
            }
        }
        if (nodes.isEmpty()) {
            return List.of();
        }
        nodes.sort(Comparator.<double[]>comparingDouble(node -> node[0]).thenComparingDouble(node -> node[2]));

        final int n = nodes.size();
        final double[] best = new double[n];
        final int[] previous = new int[n];

        for (int a = 0; a < n; a++) {
            best[a] = nodes.get(a)[1];
            previous[a] = -1;
            for (int b = 0; b < a; b++) {
                if (nodes.get(b)[0] < nodes.get(a)[0] && nodes.get(b)[2] < nodes.get(a)[2]) {
                    final double dt = lines.get((int) nodes.get(a)[0]).seconds() - lines.get((int) nodes.get(b)[0]).seconds();
                    final double dq = nodes.get(a)[2] - nodes.get(b)[2];

                    if (dt <= 0 || dq > dt * 8 + 4 || dq < dt * 0.15 - 4) {
                        continue;
                    }

                    final double value = best[b] + nodes.get(a)[1];

                    if (value > best[a]) {
                        best[a] = value;
                        previous[a] = b;
                    }
                }
            }
        }

        int end = 0;

        for (int a = 1; a < n; a++) {
            if (best[a] > best[end]) {
                end = a;
            }
        }

        final List<Anchor> chain = new ArrayList<>();

        for (int e = end; e >= 0; e = previous[e]) {
            final double[] node = nodes.get(e);

            chain.addFirst(new Anchor(lines.get((int) node[0]).seconds(), node[2]));
        }
        return prune(chain);
    }

    /**
     * Drops anchors whose tempo on both sides is far from the median tempo.
     */
    static List<Anchor> prune(final List<Anchor> anchors) {
        if (anchors.size() < 4) {
            return anchors;
        }

        final boolean[] keep = new boolean[anchors.size()];

        Arrays.fill(keep, true);
        for (int round = 0; round < 3; round++) {
            final List<Integer> kept = new ArrayList<>();

            for (int i = 0; i < keep.length; i++) {
                if (keep[i]) {
                    kept.add(i);
                }
            }
            if (kept.size() < 4) {
                break;
            }

            final double[] rates = new double[kept.size() - 1];

            for (int k = 1; k < kept.size(); k++) {
                final Anchor a = anchors.get(kept.get(k - 1));
                final Anchor b = anchors.get(kept.get(k));

                rates[k - 1] = (b.quarter() - a.quarter()) / Math.max(b.seconds() - a.seconds(), 0.2);
            }

            final double rate = median(rates);

            for (int k = 1; k < kept.size() - 1; k++) {
                final Anchor a = anchors.get(kept.get(k - 1));
                final Anchor b = anchors.get(kept.get(k));
                final Anchor c = anchors.get(kept.get(k + 1));
                final double r1 = (b.quarter() - a.quarter()) / Math.max(b.seconds() - a.seconds(), 0.2);
                final double r2 = (c.quarter() - b.quarter()) / Math.max(c.seconds() - b.seconds(), 0.2);

                if ((r1 > 4 * rate + 1 || r1 < rate / 5 - 1) && (r2 > 4 * rate + 1 || r2 < rate / 5 - 1)) {
                    keep[kept.get(k)] = false;
                }
            }
        }

        final List<Anchor> result = new ArrayList<>();

        for (int i = 0; i < anchors.size(); i++) {
            if (keep[i]) {
                result.add(anchors.get(i));
            }
        }
        return result;
    }

    /**
     * Adds a soft penalty to cells far from the time the anchors predict for their score frame.
     */
    static Matrix corridor(final Matrix c, final List<Anchor> anchors, final Knots knots) {
        if (anchors.size() < 3) {
            return c;
        }

        final double[] times = anchors.stream().mapToDouble(Anchor::seconds).toArray();
        final double[] nominal = anchors.stream().mapToDouble(anchor -> interp(anchor.quarter(), knots.quarters(), knots.seconds())).toArray();
        final float[] cells = c.cells().clone();

        for (int j = 0; j < c.columns(); j++) {
            final double t = j * HOP;

            if (t < nominal[0] || t > nominal[nominal.length - 1]) {
                continue;
            }

            final double predicted = interp(t, nominal, times);

            for (int i = 0; i < c.rows(); i++) {
                final double deviation = Math.abs(i * HOP - predicted) - 6.0;

                if (deviation > 0) {
                    cells[i * c.columns() + j] += (float) Math.min(deviation * 0.04, 1.0);
                }
            }
        }
        return new Matrix(c.rows(), c.columns(), cells);
    }

    // ------------------------------------------------------------------------------------------------ the result

    /**
     * The share of anchors within two seconds of where the path puts them, or null without anchors.
     */
    static @Nullable Double agreement(final List<int[]> path, final List<Anchor> anchors, final Knots knots) {
        if (anchors.isEmpty()) {
            return null;
        }

        final TreeMap<Integer, List<Integer>> byScore = new TreeMap<>();

        path.forEach(step -> byScore.computeIfAbsent(step[1], _ -> new ArrayList<>()).add(step[0]));

        int ok = 0;

        for (final Anchor anchor : anchors) {
            final int j = (int) Math.round(interp(anchor.quarter(), knots.quarters(), knots.seconds()) / HOP);
            final Integer floor = byScore.floorKey(j);
            final Integer ceiling = byScore.ceilingKey(j);
            final int nearest = floor == null ? java.util.Objects.requireNonNull(ceiling)
                    : ceiling == null || Math.abs(floor - j) < Math.abs(ceiling - j) ? floor : ceiling;
            final double t = byScore.get(nearest).stream().mapToInt(Integer::intValue).average().orElse(0) * HOP;

            ok += Math.abs(t - anchor.seconds()) <= 2.0 ? 1 : 0;
        }
        return (double) ok / anchors.size();
    }

    /**
     * The share of the path whose tempo over two seconds is between 0.4 and 2.5 times the score's.
     */
    static double slope(final List<int[]> path) {
        final int w = 20;

        if (path.size() < w) {
            return 0;
        }

        int ok = 0;

        for (int k = w; k < path.size(); k++) {
            final int di = path.get(k)[0] - path.get(k - w)[0];
            final int dj = path.get(k)[1] - path.get(k - w)[1];
            final double s = (double) dj / Math.max(di, 1);

            ok += s > 0.4 && s < 2.5 ? 1 : 0;
        }
        return (double) ok / (path.size() - w);
    }

    /**
     * The path as points, one a second: recording seconds, written measure and quarter notes into it.
     */
    static List<SyncPoint> points(final List<int[]> path, final Score score, final int[] order, final Knots knots) {
        final TreeMap<Integer, List<Integer>> byRecording = new TreeMap<>();

        path.forEach(step -> byRecording.computeIfAbsent(step[0], _ -> new ArrayList<>()).add(step[1]));

        final double[] starts = starts(score, order);
        final List<SyncPoint> points = new ArrayList<>();
        double last = -1e9;
        double lastQuarter = -1e9;

        for (final Map.Entry<Integer, List<Integer>> entry : byRecording.entrySet()) {
            final double t = entry.getKey() * HOP;

            if (t - last < SPLIT) {
                continue;
            }

            final double j = median(entry.getValue().stream().mapToDouble(Integer::doubleValue).toArray());
            final double q = interp(j * HOP, knots.seconds(), knots.quarters());

            if (q < lastQuarter) {
                continue;
            }

            int k = 0;

            while (k + 1 < starts.length && starts[k + 1] <= q) {
                k++;
            }

            final double beat = Math.min(Math.max(0, q - starts[k]), score.measures().get(order[k]).length());

            points.add(new SyncPoint(Math.round(t * 100) / 100.0, order[k], Math.round(beat * 1000) / 1000.0));
            last = t;
            lastQuarter = q;
        }
        return points;
    }

    /**
     * Aligns a recording with a score.
     *
     * @param samples mono, at {@link #SAMPLE_RATE}
     * @param lyrics  the track's synchronised lyrics (LRC), or null
     * @throws IllegalArgumentException when the recording or the score is too long or too short to align
     */
    public static Result align(final Score score, final float[] samples, final @Nullable String lyrics) {
        final AudioChroma recording = audioChroma(samples);

        if (recording.chroma().length < 20) {
            throw new TextException(StoredText.of("scoreSync.failed.tooShort"));
        }

        final List<LyricLine> lines = lrc(lyrics);
        final Map<String, Stream> streams = streams(score);
        final List<Boolean> variants = score.measures().stream().anyMatch(Measure::backward) ? List.of(true, false) : List.of(true);
        Candidate2 best = null;

        for (final boolean repeats : variants) {
            final int[] order = unroll(score.measures(), repeats);
            final Knots knots = knots(score, order);
            final List<Anchor> anchors = lines.isEmpty() || streams.isEmpty() ? List.of() : anchors(lines, matchLines(lines, streams), score, order);
            final double[][] chroma = scoreChroma(score, order, knots);

            if ((long) recording.chroma().length * chroma.length > MAX_CELLS) {
                throw new TextException(StoredText.of("scoreSync.failed.tooLong"));
            }
            if (chroma.length < 2) {
                continue;
            }
            for (final int shift : shifts(recording.chroma(), chroma)) {
                final Matrix c = cost(recording.chroma(), chroma, shift);
                final List<int[]> path = dtw(anchors.isEmpty() ? c : corridor(c, anchors, knots));
                final double cost = path.stream().mapToDouble(step -> c.at(step[0], step[1])).average().orElse(1);

                if (best == null || cost < best.cost()) {
                    best = new Candidate2(order, knots, anchors, path, cost, c.mean(), shift);
                }
            }
        }
        if (best == null) {
            throw new TextException(StoredText.of("scoreSync.failed.noNotes"));
        }

        final Double agreement = agreement(best.path(), best.anchors(), best.knots());
        final double margin = Math.max(0, 1 - best.cost() / Math.max(best.base(), 1e-6));
        final double slope = slope(best.path());
        final double fromMargin = clamp((margin - 0.35) / 0.30);
        final double fromSlope = clamp((slope - 0.6) / 0.3);
        final double fromLyrics = agreement != null && best.anchors().size() >= 4 ? 0.4 + 0.6 * agreement : 0.8;
        final double quality = Math.round(clamp(fromMargin * fromSlope * fromLyrics) * 1000) / 1000.0;

        return new Result(points(best.path(), score, best.order(), best.knots()), quality, best.anchors().size(), best.shift(), recording.tuning());
    }

    private static double clamp(final double value) {
        return Math.min(1, Math.max(0, value));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * Linear interpolation, clamped at the ends, as numpy's interp.
     */
    static double interp(final double x, final double[] xs, final double[] ys) {
        if (xs.length == 0) {
            return 0;
        }
        if (x <= xs[0]) {
            return ys[0];
        }
        if (x >= xs[xs.length - 1]) {
            return ys[ys.length - 1];
        }

        int lo = 0;
        int hi = xs.length - 1;

        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;

            if (xs[mid] <= x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return xs[hi] == xs[lo] ? ys[hi] : ys[lo] + (x - xs[lo]) / (xs[hi] - xs[lo]) * (ys[hi] - ys[lo]);
    }

    static double median(final double[] values) {
        if (values.length == 0) {
            return 0;
        }

        final double[] sorted = values.clone();

        Arrays.sort(sorted);
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    record Knots(double[] quarters, double[] seconds) {
    }

    record AudioChroma(double[][] chroma, double tuning) {
    }

    record Matrix(int rows, int columns, float[] cells) {

        float at(final int i, final int j) {
            return cells[i * columns + j];
        }

        double mean() {
            double sum = 0;

            for (final float cell : cells) {
                sum += cell;
            }
            return sum / Math.max(1, cells.length);
        }
    }

    record LyricLine(double seconds, String text) {
    }

    record Stream(String text, List<double[]> index) {
    }

    record Candidate(double ratio, int measure, double beat) {
    }

    /**
     * @param quarter quarter notes into the score, played out
     */
    record Anchor(double seconds, double quarter) {
    }

    private record Candidate2(int[] order, Knots knots, List<Anchor> anchors, List<int[]> path, double cost, double base, int shift) {
    }

    /**
     * A moment of the recording and where in the score it is.
     *
     * @param seconds into the recording
     * @param measure the measure's index in written order, from 0
     * @param beat    quarter notes from the measure's start
     */
    public record SyncPoint(double seconds, int measure, double beat) {
    }

    /**
     * @param points  in the order of the recording, seconds strictly increasing
     * @param quality from 0 (useless) to 1
     * @param anchors how many lyric lines anchored it
     * @param shift   the transposition, in semitones, that matched best
     * @param tuning  the recording's tuning, in semitones from A440
     */
    public record Result(List<SyncPoint> points, double quality, int anchors, int shift, double tuning) {
    }
}
