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

import nu.fgv.spexify.server.util.StoredText;
import nu.fgv.spexify.server.util.TextException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A MusicXML score (score-partwise, plain or compressed as {@code .mxl}) as the aligner needs it: its measures in
 * written order with their lengths, repeats and numbered endings, its notes at sounding pitch, its lyric syllables and
 * its tempo marks. Times are in quarter notes; a measure index is the measure's place in written order. Grace notes,
 * ornaments and D.C./D.S./coda jumps are not followed. The same rules as the web's reader ({@code score-parse.ts}),
 * ported from the old Spexify's {@code score_align.py}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class MusicXml {

    private static final Map<String, Integer> STEP = Map.of("C", 0, "D", 2, "E", 4, "F", 5, "G", 7, "A", 9, "B", 11);
    private static final Map<String, Double> UNIT = Map.of("whole", 4.0, "half", 2.0, "quarter", 1.0, "eighth", 0.5, "16th", 0.25);
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[\\d.]+");
    private static final int MAX_BYTES = 50 * 1024 * 1024;

    private MusicXml() {
    }

    /**
     * Reads a score.
     *
     * @throws IOException when it is not MusicXML the aligner can read
     */
    public static Score parse(final byte[] content) throws IOException {
        return parse(document(content));
    }

    /**
     * The score document: a compressed one's root file (META-INF/container.xml names it, else the first .xml or
     * .musicxml outside META-INF), or the content itself.
     */
    static Document document(final byte[] content) throws IOException {
        if (content.length > 1 && content[0] == 'P' && content[1] == 'K') {
            final Map<String, byte[]> entries = new LinkedHashMap<>();

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                ZipEntry entry;
                long total = 0;

                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    final byte[] bytes = zip.readNBytes(MAX_BYTES + 1);

                    total += bytes.length;
                    if (bytes.length > MAX_BYTES || total > MAX_BYTES) {
                        throw TextException.io(StoredText.of("scoreSync.failed.tooLarge"), null);
                    }
                    entries.put(entry.getName(), bytes);
                }
            }

            String root = null;
            final byte[] container = entries.get("META-INF/container.xml");

            if (container != null) {
                final Element rootFile = first(xml(container).getDocumentElement(), "rootfile");

                root = rootFile == null ? null : rootFile.getAttribute("full-path");
            }
            if (root == null || !entries.containsKey(root)) {
                root = entries.keySet().stream()
                        .filter(name -> !name.startsWith("META-INF/") && (name.endsWith(".xml") || name.endsWith(".musicxml")))
                        .findFirst().orElseThrow(() -> TextException.io(StoredText.of("scoreSync.failed.noScore"), null));
            }
            return xml(entries.get(root));
        }
        return xml(content);
    }

    private static Document xml(final byte[] content) throws IOException {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

            // MusicXML declares a DOCTYPE, so it cannot be refused; never load it or any external entity
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            final DocumentBuilder builder = factory.newDocumentBuilder();

            builder.setEntityResolver((_, _) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(content));
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw TextException.io(StoredText.of("scoreSync.failed.notMusicXml", String.valueOf(e.getMessage())), e);
        }
    }

    static Score parse(final Document document) throws IOException {
        final Element root = document.getDocumentElement();

        if (root == null || !"score-partwise".equals(root.getTagName())) {
            throw TextException.io(StoredText.of("scoreSync.failed.notPartwise"), null);
        }

        final List<Element> parts = children(root, "part");

        if (parts.isEmpty()) {
            throw TextException.io(StoredText.of("scoreSync.failed.noParts"), null);
        }

        final List<MeasureInfo> measures = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();
        final List<Syllable> syllables = new ArrayList<>();
        final List<Tempo> tempi = new ArrayList<>();

        for (int pi = 0; pi < parts.size(); pi++) {
            final Element part = parts.get(pi);
            final String partId = part.getAttribute("id").isEmpty() ? "P" + pi : part.getAttribute("id");
            double divisions = 1;
            int transpose = 0;
            final List<RawNote> raw = new ArrayList<>();
            final Map<Integer, List<MeasureRepeats.Sign>> signs = new TreeMap<>();
            final List<Element> partMeasures = children(part, "measure");

            for (int mi = 0; mi < partMeasures.size(); mi++) {
                final Element measure = partMeasures.get(mi);
                double pos = 0;
                double max = 0;
                double lastStart = 0;
                final MeasureInfo info = new MeasureInfo();

                for (final Element el : children(measure, null)) {
                    switch (el.getTagName()) {
                        case "attributes" -> {
                            final List<MeasureRepeats.Sign> found = MeasureRepeats.signs(el);

                            if (!found.isEmpty()) {
                                signs.computeIfAbsent(mi, _ -> new ArrayList<>()).addAll(found);
                            }

                            final Element div = first(el, "divisions");

                            if (div != null) {
                                divisions = Math.max(1, (int) number(div, 1));
                            }

                            final Element transposeElement = first(el, "transpose");

                            if (transposeElement != null) {
                                transpose = (int) number(first(transposeElement, "chromatic"), 0)
                                        + 12 * (int) number(first(transposeElement, "octave-change"), 0);
                            }
                        }
                        case "backup" -> pos -= number(first(el, "duration"), 0) / divisions;
                        case "forward" -> pos += number(first(el, "duration"), 0) / divisions;
                        case "direction" -> {
                            final double offset = number(first(el, "offset"), 0) / divisions;
                            final Element sound = first(el, "sound");
                            Double bpm = sound != null && !sound.getAttribute("tempo").isEmpty() ? parseDouble(sound.getAttribute("tempo")) : null;
                            final Element metronome = first(first(el, "direction-type"), "metronome");

                            if (bpm == null && metronome != null) {
                                double unit = UNIT.getOrDefault(text(first(metronome, "beat-unit")), 1.0);

                                unit *= first(metronome, "beat-unit-dot") != null ? 1.5 : 1;

                                final Matcher matcher = DECIMAL.matcher(text(first(metronome, "per-minute")));

                                bpm = matcher.find() ? parseDouble(matcher.group()) : null;
                                bpm = bpm == null ? null : bpm * unit;
                            }
                            if (bpm != null && bpm > 0 && pi == 0) {
                                tempi.add(new Tempo(mi, Math.max(0, pos + offset), bpm));
                            }
                        }
                        case "sound" -> {
                            if (pi == 0 && !el.getAttribute("tempo").isEmpty()) {
                                final Double bpm = parseDouble(el.getAttribute("tempo"));

                                if (bpm != null && bpm > 0) {
                                    tempi.add(new Tempo(mi, Math.max(0, pos), bpm));
                                }
                            }
                        }
                        case "barline" -> {
                            if (pi == 0) {
                                final Element repeat = first(el, "repeat");

                                if (repeat != null) {
                                    if ("forward".equals(repeat.getAttribute("direction"))) {
                                        info.forward = true;
                                    } else {
                                        info.backward = true;
                                        if (!repeat.getAttribute("times").isEmpty()) {
                                            info.times = Math.max(2, (int) number(repeat.getAttribute("times"), 2));
                                        }
                                    }
                                }

                                final Element ending = first(el, "ending");

                                if (ending != null) {
                                    final Set<Integer> numbers = new HashSet<>();
                                    final Matcher matcher = NUMBER.matcher(ending.getAttribute("number"));

                                    while (matcher.find()) {
                                        numbers.add(Integer.parseInt(matcher.group()));
                                    }
                                    // A one-measure ending starts and stops in the same measure, at its two barlines
                                    if ("start".equals(ending.getAttribute("type"))) {
                                        info.endingStart = true;
                                        info.endingNumbers = numbers;
                                    } else if ("stop".equals(ending.getAttribute("type")) || "discontinue".equals(ending.getAttribute("type"))) {
                                        info.endingStop = true;
                                    }
                                }
                            }
                        }
                        case "note" -> {
                            final double duration = number(first(el, "duration"), 0) / divisions;
                            final boolean chord = first(el, "chord") != null;
                            final boolean grace = first(el, "grace") != null;
                            final double start = chord ? lastStart : pos;
                            final Element pitch = first(el, "pitch");

                            if (pitch != null && !grace && duration > 0) {
                                final int step = STEP.getOrDefault(text(first(pitch, "step")), 0);
                                final int alter = (int) number(first(pitch, "alter"), 0);
                                final int octave = (int) number(first(pitch, "octave"), 4);

                                raw.add(new RawNote(mi, (int) number(first(el, "staff"), 1), start, duration,
                                        12 * (octave + 1) + step + alter + transpose));
                            }
                            for (final Element lyric : children(el, "lyric")) {
                                final StringBuilder text = new StringBuilder();

                                children(lyric, "text").forEach(part2 -> text.append(part2.getTextContent()));
                                if (!text.toString().isBlank()) {
                                    final String number = lyric.getAttribute("number").isEmpty() ? "1" : lyric.getAttribute("number");
                                    final Element syllabic = first(lyric, "syllabic");

                                    syllables.add(new Syllable(partId + ":" + number, mi, start, text.toString(),
                                            syllabic == null ? "single" : syllabic.getTextContent().strip()));
                                }
                            }
                            if (!chord && !grace) {
                                lastStart = pos;
                                pos += duration;
                            }
                        }
                        default -> {
                        }
                    }
                    max = Math.max(max, pos);
                }
                if (pi == 0) {
                    info.length = max;
                    measures.add(info);
                } else if (mi < measures.size()) {
                    measures.get(mi).length = Math.max(measures.get(mi).length, max);
                }
            }
            for (final RawNote note : MeasureRepeats.resolve(raw, signs)) {
                notes.add(new Note(note.measure(), note.beat(), note.duration(), note.midi()));
            }
        }

        // Numbered endings: every measure from an ending's start to its stop belongs to it
        Set<Integer> current = null;
        final List<Measure> result = new ArrayList<>();
        double t = 0;

        for (final MeasureInfo info : measures) {
            if (info.endingStart) {
                current = info.endingNumbers;
            }

            final Set<Integer> volta = current == null ? Set.of() : Set.copyOf(current);

            if (info.endingStop) {
                current = null;
            }
            result.add(new Measure(t, info.length, info.forward, info.backward, info.times, volta));
            t += info.length;
        }
        if (result.isEmpty()) {
            throw TextException.io(StoredText.of("scoreSync.failed.noMeasures"), null);
        }
        return new Score(result, notes, syllables, tempi);
    }

    private static List<Element> children(final @Nullable Element parent, final @Nullable String tag) {
        final List<Element> result = new ArrayList<>();

        if (parent == null) {
            return result;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && (tag == null || tag.equals(element.getTagName()))) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * The first child with the tag; for {@code rootfile}, the first such element anywhere below.
     */
    static @Nullable Element first(final @Nullable Element parent, final String tag) {
        if (parent == null) {
            return null;
        }
        for (final Element child : children(parent, null)) {
            if (tag.equals(child.getTagName())) {
                return child;
            }
        }
        if ("rootfile".equals(tag)) {
            final var found = parent.getElementsByTagName(tag);

            return found.getLength() > 0 ? (Element) found.item(0) : null;
        }
        return null;
    }

    private static String text(final @Nullable Element element) {
        return element == null ? "" : element.getTextContent().strip();
    }

    private static double number(final @Nullable Element element, final double fallback) {
        return element == null ? fallback : number(element.getTextContent(), fallback);
    }

    private static double number(final String text, final double fallback) {
        final Double value = parseDouble(text.strip());

        return value == null ? fallback : value;
    }

    private static @Nullable Double parseDouble(final String text) {
        try {
            final double value = Double.parseDouble(text);

            return Double.isFinite(value) ? value : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * A measure while it is read.
     */
    private static final class MeasureInfo {
        private double length;
        private boolean forward;
        private boolean backward;
        private int times = 2;
        private boolean endingStart;
        private boolean endingStop;
        private Set<Integer> endingNumbers = Set.of();
    }

    /**
     * A note before measure-repeat signs are resolved, with its staff.
     */
    record RawNote(int measure, int staff, double beat, double duration, int midi) {
    }

    /**
     * @param start    quarter notes from the start of the score, in written order
     * @param length   in quarter notes
     * @param forward  a forward repeat at its left barline
     * @param backward a backward repeat at its right barline
     * @param times    how often the repeated section is played
     * @param volta    the numbered endings it belongs to, empty for none
     */
    public record Measure(double start, double length, boolean forward, boolean backward, int times, Set<Integer> volta) {
    }

    /**
     * @param beat     quarter notes from the start of its measure
     * @param duration in quarter notes
     * @param midi     the sounding pitch
     */
    public record Note(int measure, double beat, double duration, int midi) {
    }

    /**
     * @param stream   the part and lyric line, e.g. {@code P1:1}
     * @param syllabic single, begin, middle or end
     */
    public record Syllable(String stream, int measure, double beat, String text, String syllabic) {
    }

    /**
     * @param bpm quarter notes per minute
     */
    public record Tempo(int measure, double beat, double bpm) {
    }

    public record Score(List<Measure> measures, List<Note> notes, List<Syllable> syllables, List<Tempo> tempi) {

        /**
         * Its text, for tests: the syllables of every stream.
         */
        Map<String, String> text() {
            final Map<String, String> result = new HashMap<>();

            syllables.forEach(syllable -> result.merge(syllable.stream(), syllable.text(), String::concat));
            return result;
        }
    }

    static String decode(final byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}
