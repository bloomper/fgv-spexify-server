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

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * MusicXML's measure-repeat signs ("repeat the previous bar", "the previous N bars"), so that the repeated bars sound.
 * Sibelius writes the start, with N, in the attributes of the first repeated bar, then bars of whole-bar rests, and the
 * stop in the attributes of the first bar after the span. A repeated bar sounds like the bar N places earlier in written
 * order, already resolved, per staff; {@code number} on measure-style names the staff, none means every staff. A bar with
 * notes of its own is never replaced. The same rule as the web's {@code score-repeat.ts}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class MeasureRepeats {

    private MeasureRepeats() {
    }

    static List<Sign> signs(final Element attributes) {
        final List<Sign> result = new ArrayList<>();

        for (Node node = attributes.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element style) || !"measure-style".equals(style.getTagName())) {
                continue;
            }

            Integer staff;

            try {
                staff = style.getAttribute("number").isEmpty() ? null : Integer.parseInt(style.getAttribute("number").strip());
            } catch (final NumberFormatException e) {
                staff = null;
            }
            for (Node child = style.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element repeat && "measure-repeat".equals(repeat.getTagName())) {
                    if ("start".equals(repeat.getAttribute("type"))) {
                        int n;

                        try {
                            n = Math.max(1, Integer.parseInt(repeat.getTextContent().strip()));
                        } catch (final NumberFormatException e) {
                            n = 1;
                        }
                        result.add(new Sign(staff, true, n));
                    } else if ("stop".equals(repeat.getAttribute("type"))) {
                        result.add(new Sign(staff, false, 0));
                    }
                }
            }
        }
        return result;
    }

    static List<MusicXml.RawNote> resolve(final List<MusicXml.RawNote> notes, final Map<Integer, List<Sign>> signs) {
        if (signs.isEmpty()) {
            return notes;
        }

        final Map<String, List<MusicXml.RawNote>> byBar = new HashMap<>();

        notes.forEach(note -> byBar.computeIfAbsent(note.measure() + ":" + note.staff(), _ -> new ArrayList<>()).add(note));

        final Set<String> own = Set.copyOf(byBar.keySet());
        final Set<Integer> staves = new TreeSet<>(List.of(1));

        notes.forEach(note -> staves.add(note.staff()));

        final int first = signs.keySet().stream().min(Integer::compare).orElse(0);
        final int last = Math.max(signs.keySet().stream().max(Integer::compare).orElse(0),
                notes.stream().mapToInt(MusicXml.RawNote::measure).max().orElse(0));
        final Map<@Nullable Integer, Integer> active = new HashMap<>();
        final List<MusicXml.RawNote> extra = new ArrayList<>();

        for (int m = first; m <= last; m++) {
            final List<@Nullable Integer> stops = new ArrayList<>();

            for (final Sign sign : signs.getOrDefault(m, List.of())) {
                if (sign.start()) {
                    active.put(sign.staff(), sign.n());
                } else {
                    stops.add(sign.staff());
                }
            }
            for (final int staff : staves) {
                final Integer n = active.containsKey(staff) ? active.get(staff) : active.get(null);

                if (n == null || n == 0 || m - n < 0) {
                    continue;
                }
                if (stops.contains(staff) || stops.contains(null)) {
                    final int bar = m;

                    if (staves.stream().anyMatch(other -> own.contains(bar + ":" + other))) {
                        continue;
                    }
                } else if (own.contains(m + ":" + staff)) {
                    continue;
                }
                for (final MusicXml.RawNote note : List.copyOf(byBar.getOrDefault((m - n) + ":" + staff, List.of()))) {
                    final MusicXml.RawNote copy = new MusicXml.RawNote(m, staff, note.beat(), note.duration(), note.midi());

                    extra.add(copy);
                    byBar.computeIfAbsent(m + ":" + staff, _ -> new ArrayList<>()).add(copy);
                }
            }
            stops.forEach(active::remove);
        }

        final List<MusicXml.RawNote> result = new ArrayList<>(notes);

        result.addAll(extra);
        return result;
    }

    /**
     * @param staff the staff it applies to, null for every staff of the part
     * @param start a start sign, else a stop sign
     * @param n     how many bars back the repeated bars sound like
     */
    record Sign(@Nullable Integer staff, boolean start, int n) {
    }
}
