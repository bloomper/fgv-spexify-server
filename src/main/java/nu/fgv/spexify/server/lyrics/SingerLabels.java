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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singer labels in lyrics, such as {@code Ph:}, {@code Ph/Pa:} or {@code SyCrEm:}, and the names of the spex's
 * characters they stand for. Pure and driven by tables, ported from the old Spexify's {@code singers.py}: a label
 * resolves only when exactly one character fits at the strongest test that any fits, so a label is never guessed; one
 * that does not resolve keeps its text.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class SingerLabels {

    /**
     * Labels for everyone, which stay as they are.
     */
    static final Pattern CHOIR = Pattern.compile("^(k[oö]r(en)?|alla|choir|resten|alla\\s*utom\\b.*|allautom.*|alla\\s*\\(.*\\))$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:/|&|\\+|,|;|\\boch\\b)\\s*", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> TOKENS = List.of(
            Pattern.compile("[A-ZÅÄÖÜÉ][a-zåäöüé]+"),
            Pattern.compile("[A-ZÅÄÖÜÉ]{2}(?![a-zåäöüé])"),
            Pattern.compile("[A-ZÅÄÖÜÉ]"));
    private static final int MAX_GLUED = 18;
    private static final Pattern ALL_CAPS = Pattern.compile("^[A-ZÅÄÖÜÉ]{2,}$");
    private static final List<String> TIERS_CAPS = List.of("alias", "initials", "name", "first", "word");
    private static final List<String> TIERS = List.of("alias", "name", "first", "word", "initials");
    private static final Pattern ALIAS = Pattern.compile("\\(([^()\\s]{1,6})\\)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+(?:[-'][\\p{L}]+)*");
    private static final Pattern PARENS = Pattern.compile("\\(.*?\\)");
    /**
     * A label at the start of a line, after any timestamps: a short run without a colon, then a colon and a space.
     */
    private static final Pattern LINE_LABEL = Pattern.compile("^\\s*(?:\\[[\\d:.]+]\\s*)*([\\p{L}][\\p{L} ./&+,;()-]{0,23}?)\\s*:\\s+\\S");

    private SingerLabels() {
    }

    /**
     * The distinct labels at the start of the lines, in order.
     */
    public static List<String> labels(final @Nullable String lyrics) {
        final Set<String> labels = new LinkedHashSet<>();

        if (lyrics != null) {
            for (final String line : lyrics.split("\\R")) {
                final Matcher matcher = LINE_LABEL.matcher(line);

                if (matcher.find()) {
                    labels.add(matcher.group(1).strip());
                }
            }
        }
        return List.copyOf(labels);
    }

    /**
     * What each label of the lyrics stands for: choir labels as they are, others only when they resolve.
     */
    public static Map<String, String> resolve(final @Nullable String lyrics, final List<String> characters) {
        final List<Person> people = people(characters);
        final Map<String, String> result = new LinkedHashMap<>();

        for (final String label : labels(lyrics)) {
            if (CHOIR.matcher(label).matches()) {
                result.put(label, label);
                continue;
            }

            final String name = resolveLabel(label, people);

            if (name != null) {
                result.put(label, name);
            }
        }
        return result;
    }

    /**
     * The characters as people: the same first name with nested names (Cremona and Cremona Fix) is one person; people
     * who share a first name are shown by their full names.
     */
    static List<Person> people(final List<String> characters) {
        final List<Person> people = new ArrayList<>();

        for (final String character : characters) {
            final List<String> words = words(character);

            if (words.isEmpty()) {
                continue;
            }

            final List<String> key = words.stream().map(word -> word.toLowerCase(Locale.ROOT)).toList();
            Person person = null;

            for (final Person candidate : people) {
                if (nested(candidate.key, key)) {
                    person = candidate;
                    if (key.size() > candidate.key.size()) {
                        candidate.key = key;
                        candidate.name = clean(character);
                    }
                    break;
                }
            }
            if (person == null) {
                person = new Person(key, clean(character), words.getFirst());
                people.add(person);
            }

            final Matcher alias = ALIAS.matcher(character);

            while (alias.find()) {
                person.aliases.add(alias.group(1).toLowerCase(Locale.ROOT));
            }
            person.keys.add(key);
        }

        final Map<String, Integer> firstNames = new HashMap<>();

        people.forEach(person -> firstNames.merge(person.key.getFirst(), 1, Integer::sum));
        people.forEach(person -> person.display = firstNames.get(person.key.getFirst()) > 1 ? person.name : person.first);
        return people;
    }

    private static boolean nested(final List<String> a, final List<String> b) {
        if (!a.getFirst().equals(b.getFirst())) {
            return false;
        }

        final int n = Math.min(a.size(), b.size());

        return a.subList(0, n).equals(b.subList(0, n));
    }

    private static String clean(final String character) {
        return PARENS.matcher(character.split(",")[0]).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    private static List<String> words(final String character) {
        final List<String> words = new ArrayList<>();
        final Matcher matcher = WORD.matcher(PARENS.matcher(character.split(",")[0]).replaceAll(" "));

        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static boolean test(final String tier, final String label, final Person person) {
        return switch (tier) {
            case "alias" -> person.aliases.contains(label);
            case "name" -> label.equals(String.join(" ", person.key)) || label.equals(person.key.getFirst());
            case "first" -> person.key.getFirst().startsWith(label);
            case "word" -> person.key.stream().anyMatch(word -> word.startsWith(label));
            case "initials" -> label.length() >= 2 && person.keys.stream().anyMatch(key -> {
                final StringBuilder initials = new StringBuilder();

                key.forEach(word -> initials.append(word.charAt(0)));
                return label.equals(initials.toString());
            });
            default -> false;
        };
    }

    /**
     * The one person a single label means, or null when none or several fit.
     */
    static @Nullable Person match(final String raw, final List<Person> people) {
        final String label = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[.:]+$", "");

        if (label.isEmpty() || people.isEmpty()) {
            return null;
        }
        for (final String tier : ALL_CAPS.matcher(raw.strip()).matches() ? TIERS_CAPS : TIERS) {
            final List<Person> hits = people.stream().filter(person -> test(tier, label, person)).toList();

            if (hits.size() == 1) {
                return hits.getFirst();
            }
            if (hits.size() > 1) {
                // Ambiguous at the strongest test that fits: never guess with a weaker one
                return null;
            }
        }
        return null;
    }

    /**
     * A label like OlVaPr or JuCh split into pieces that each mean a different person, or null.
     */
    static @Nullable List<Person> glued(final String label, final List<Person> people) {
        if (label.length() > MAX_GLUED || !label.chars().allMatch(Character::isLetter)) {
            return null;
        }

        final List<Person> pieces = segment(label, people, 0, new HashSet<>());

        return pieces != null && pieces.size() >= 2 ? pieces : null;
    }

    private static @Nullable List<Person> segment(final String label, final List<Person> people, final int at, final Set<Person> used) {
        if (at == label.length()) {
            return new ArrayList<>();
        }
        for (final Pattern token : TOKENS) {
            final Matcher matcher = token.matcher(label).region(at, label.length());

            if (!matcher.lookingAt()) {
                continue;
            }

            final Person one = match(matcher.group(), people);

            if (one != null && !used.contains(one)) {
                final Set<Person> next = new HashSet<>(used);

                next.add(one);

                final List<Person> rest = segment(label, people, matcher.end(), next);

                if (rest != null) {
                    rest.addFirst(one);
                    return rest;
                }
            }
        }
        return null;
    }

    static String join(final List<String> names) {
        return names.size() == 1 ? names.getFirst() : String.join(", ", names.subList(0, names.size() - 1)) + " & " + names.getLast();
    }

    /**
     * The names a label stands for, or null when it does not resolve.
     */
    static @Nullable String resolveLabel(final String label, final List<Person> people) {
        if (people.isEmpty()) {
            return null;
        }

        final List<String> parts = java.util.Arrays.stream(SPLIT.split(label.strip())).filter(part -> !part.isEmpty()).toList();

        if (parts.size() > 1) {
            final List<String> names = new ArrayList<>();
            boolean resolved = false;

            for (final String part : parts) {
                List<String> found = null;

                if (CHOIR.matcher(part).matches()) {
                    found = List.of(part);
                } else {
                    final Person one = match(part, people);

                    if (one != null) {
                        found = List.of(one.display);
                        resolved = true;
                    } else {
                        final List<Person> glued = glued(part, people);

                        if (glued != null) {
                            found = glued.stream().map(person -> person.display).toList();
                            resolved = true;
                        }
                    }
                }
                for (final String name : found == null ? List.of(part) : found) {
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                }
            }
            return resolved ? join(names) : null;
        }

        final Person one = match(label, people);

        if (one != null) {
            return one.display;
        }

        final List<Person> glued = glued(label.strip(), people);

        return glued == null ? null : join(glued.stream().map(person -> person.display).toList());
    }

    /**
     * A character, or characters who are the same person.
     */
    static final class Person {
        private final String first;
        private final Set<String> aliases = new HashSet<>();
        private final Set<List<String>> keys = new HashSet<>();
        private List<String> key;
        private String name;
        private String display;

        Person(final List<String> key, final String name, final String first) {
            this.key = key;
            this.name = name;
            this.first = first;
            this.display = first;
        }

        String display() {
            return display;
        }
    }
}
