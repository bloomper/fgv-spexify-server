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

package nu.fgv.spexify.server.navidrome;

import nu.fgv.spexify.server.config.SpexifyProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls Navidrome's Subsonic API on the internal network, with the user named in {@code Remote-User}. Navidrome trusts
 * that header only from this server.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class NavidromeClient {

    public static final String USER_HEADER = "Remote-User";
    public static final String API_VERSION = "1.16.1";
    public static final String CLIENT = "spexify";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int PAGE = 500;

    private final HttpClient httpClient;
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> knownUsers = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(10_000)
            .build();
    private final JsonMapper jsonMapper;
    private final String baseUrl;
    private final String systemUser;

    public NavidromeClient(final SpexifyProperties properties, final JsonMapper jsonMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.jsonMapper = jsonMapper;
        this.baseUrl = properties.navidrome().url().replaceAll("/+$", "");
        this.systemUser = properties.navidrome().systemUser();
    }

    public static String encode(final Map<String, List<String>> parameters) {
        return parameters.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(value ->
                        URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));
    }

    static @Nullable Instant instant(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    public String systemUser() {
        return systemUser;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public URI uri(final String endpoint, final Map<String, List<String>> parameters) {
        return URI.create(baseUrl + "/rest/" + endpoint + (parameters.isEmpty() ? "" : "?" + encode(parameters)));
    }

    /**
     * Makes sure Navidrome has a user with this name. Navidrome creates users from {@code Remote-User} only when its
     * web index is opened, not on the Subsonic API, so the gateway opens it once per user (and again an hour later,
     * in case Navidrome's database was replaced). On a fresh Navidrome the first user becomes admin, so the system
     * user is ensured first, at start.
     */
    public void ensureUser(final String username) throws IOException {
        if (knownUsers.getIfPresent(username) != null) {
            return;
        }

        final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app/"))
                .timeout(TIMEOUT)
                .header(USER_HEADER, username)
                .GET()
                .build();

        try {
            final HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() != 200) {
                throw new IOException("Navidrome answered " + response.statusCode() + " when creating " + username);
            }
            knownUsers.put(username, Boolean.TRUE);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /**
     * Calls an endpoint as the system user, which is Navidrome's admin, and returns the {@code subsonic-response}.
     */
    public JsonNode system(final String endpoint, final Map<String, String> parameters) throws IOException {
        return as(systemUser, endpoint, parameters);
    }

    /**
     * Calls an endpoint as a user and returns the {@code subsonic-response}.
     */
    public JsonNode as(final String username, final String endpoint, final Map<String, String> parameters) throws IOException {
        ensureUser(username);

        final Map<String, List<String>> all = new LinkedHashMap<>();

        all.put("v", List.of(API_VERSION));
        all.put("c", List.of(CLIENT));
        all.put("f", List.of("json"));
        parameters.forEach((key, value) -> all.put(key, List.of(value)));

        final HttpRequest request = HttpRequest.newBuilder(uri(endpoint, all))
                .timeout(TIMEOUT)
                .header(USER_HEADER, username)
                .GET()
                .build();

        try {
            final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Navidrome answered " + response.statusCode() + " to " + endpoint);
                }

                final JsonNode root = jsonMapper.readTree(body).path("subsonic-response");

                if (root.path("error").path("code").asInt(0) == 40) {
                    // Navidrome lost the user, e.g. its database was replaced
                    knownUsers.invalidate(username);
                }
                if (!"ok".equals(root.path("status").asString(""))) {
                    throw new IOException("Navidrome failed " + endpoint + ": " + root.path("error").path("message").asString(""));
                }
                return root;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    /**
     * Makes sure the system user exists. On a fresh Navidrome the first user becomes admin, so this runs before anyone
     * else's request reaches it.
     */
    public void ping() throws IOException {
        system("ping", Map.of());
    }

    public void startScan(final boolean full) throws IOException {
        system("startScan", full ? Map.of("fullScan", "true") : Map.of());
    }

    public ScanStatus scanStatus() throws IOException {
        final JsonNode status = system("getScanStatus", Map.of()).path("scanStatus");

        return new ScanStatus(status.path("scanning").asBoolean(false), status.path("count").asLong(0),
                status.path("lastScan").asString(null));
    }

    /**
     * Every song Navidrome knows, with its id, path and MusicBrainz id, page by page through {@code search3} with an
     * empty query.
     */
    public List<Song> songs() throws IOException {
        final List<Song> songs = new ArrayList<>();

        for (int offset = 0; ; offset += PAGE) {
            final JsonNode page = system("search3", Map.of("query", "", "artistCount", "0", "albumCount", "0",
                    "songCount", Integer.toString(PAGE), "songOffset", Integer.toString(offset)))
                    .path("searchResult3").path("song");

            for (final JsonNode song : page) {
                songs.add(new Song(song.path("id").asString(""), song.path("albumId").asString(""),
                        song.path("path").asString(""), song.path("musicBrainzId").asString("")));
            }
            if (page.size() < PAGE) {
                return songs;
            }
        }
    }

    /**
     * What a user has rated, starred and played of every song, page by page through {@code search3} with an empty
     * query. Songs the user has done nothing with are left out.
     */
    public List<SongState> songStates(final String username) throws IOException {
        final List<SongState> states = new ArrayList<>();

        for (int offset = 0; ; offset += PAGE) {
            final JsonNode page = as(username, "search3", Map.of("query", "", "artistCount", "0", "albumCount", "0",
                    "songCount", Integer.toString(PAGE), "songOffset", Integer.toString(offset)))
                    .path("searchResult3").path("song");

            for (final JsonNode song : page) {
                final SongState state = new SongState(song.path("id").asString(""), song.path("userRating").asInt(0),
                        instant(song.path("starred").asString(null)), song.path("playCount").asInt(0),
                        instant(song.path("played").asString(null)));

                if (state.rating() > 0 || state.starred() != null || state.playCount() > 0) {
                    states.add(state);
                }
            }
            if (page.size() < PAGE) {
                return states;
            }
        }
    }

    /**
     * What a user has rated and starred of every album, page by page through {@code getAlbumList2}. Albums the user
     * has done nothing with are left out.
     */
    public List<AlbumState> albumStates(final String username) throws IOException {
        final List<AlbumState> states = new ArrayList<>();

        for (int offset = 0; ; offset += PAGE) {
            final JsonNode page = as(username, "getAlbumList2", Map.of("type", "alphabeticalByName",
                    "size", Integer.toString(PAGE), "offset", Integer.toString(offset)))
                    .path("albumList2").path("album");

            for (final JsonNode album : page) {
                final AlbumState state = new AlbumState(album.path("id").asString(""), album.path("userRating").asInt(0),
                        instant(album.path("starred").asString(null)));

                if (state.rating() > 0 || state.starred() != null) {
                    states.add(state);
                }
            }
            if (page.size() < PAGE) {
                return states;
            }
        }
    }

    public record SongState(String id, int rating, @Nullable Instant starred, int playCount,
                            @Nullable Instant played) {
    }

    public record AlbumState(String id, int rating, @Nullable Instant starred) {
    }

    public record ScanStatus(boolean scanning, long count, @Nullable String lastScan) {
    }

    public record Song(String id, String albumId, String path, String musicBrainzId) {
    }
}
