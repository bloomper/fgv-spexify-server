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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Python's {@code difflib.SequenceMatcher} on two strings, without junk: the matching blocks and the similarity ratio
 * the aligner's lyric matching was tuned with.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class SequenceMatcher {

    private final String a;
    private final String b;
    private final Map<Character, List<Integer>> positions = new HashMap<>();
    private List<int[]> blocks;

    SequenceMatcher(final String a, final String b) {
        this.a = a;
        this.b = b;
        for (int j = 0; j < b.length(); j++) {
            positions.computeIfAbsent(b.charAt(j), _ -> new ArrayList<>()).add(j);
        }
    }

    /**
     * The longest common block in a[alo:ahi] and b[blo:bhi], earliest in a, then in b: (i, j, size).
     */
    private int[] longest(final int alo, final int ahi, final int blo, final int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestSize = 0;
        Map<Integer, Integer> lengths = new HashMap<>();

        for (int i = alo; i < ahi; i++) {
            final Map<Integer, Integer> next = new HashMap<>();

            for (final int j : positions.getOrDefault(a.charAt(i), List.of())) {
                if (j < blo) {
                    continue;
                }
                if (j >= bhi) {
                    break;
                }

                final int k = lengths.getOrDefault(j - 1, 0) + 1;

                next.put(j, k);
                if (k > bestSize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestSize = k;
                }
            }
            lengths = next;
        }
        return new int[]{besti, bestj, bestSize};
    }

    /**
     * The matching blocks, as (i, j, size), in order, adjacent ones merged, without Python's final empty block.
     */
    List<int[]> matchingBlocks() {
        if (blocks != null) {
            return blocks;
        }

        final List<int[]> found = new ArrayList<>();
        final Deque<int[]> queue = new ArrayDeque<>();

        queue.push(new int[]{0, a.length(), 0, b.length()});
        while (!queue.isEmpty()) {
            final int[] range = queue.pop();
            final int[] match = longest(range[0], range[1], range[2], range[3]);

            if (match[2] > 0) {
                found.add(match);
                if (range[0] < match[0] && range[2] < match[1]) {
                    queue.push(new int[]{range[0], match[0], range[2], match[1]});
                }
                if (match[0] + match[2] < range[1] && match[1] + match[2] < range[3]) {
                    queue.push(new int[]{match[0] + match[2], range[1], match[1] + match[2], range[3]});
                }
            }
        }
        found.sort((x, y) -> x[0] != y[0] ? Integer.compare(x[0], y[0]) : Integer.compare(x[1], y[1]));

        final List<int[]> merged = new ArrayList<>();

        for (final int[] block : found) {
            if (!merged.isEmpty()) {
                final int[] last = merged.getLast();

                if (last[0] + last[2] == block[0] && last[1] + last[2] == block[1]) {
                    last[2] += block[2];
                    continue;
                }
            }
            merged.add(block.clone());
        }
        blocks = merged;
        return blocks;
    }

    double ratio() {
        final int total = a.length() + b.length();
        final int matches = matchingBlocks().stream().mapToInt(block -> block[2]).sum();

        return total == 0 ? 1.0 : 2.0 * matches / total;
    }
}
