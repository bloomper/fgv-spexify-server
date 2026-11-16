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

package nu.fgv.spexify.server.mcp;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP server: only reading tools, the catalogue as the caller may see it, and only {@code fgv-spexify-mcp} tokens
 * with the MCP endpoint as audience.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class McpToolsIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    @TempDir
    private Path folder;

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    private static Map<String, Object> args(final Object... pairs) {
        final Map<String, Object> map = new HashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private Response post(final @Nullable String token, final Map<String, Object> body) {
        return http().post()
                .uri(MCP_PATH)
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(body)
                .exchange((request, response) -> new Response(response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private JsonNode rpc(final String token, final String method, final Map<String, Object> params) {
        final Response response = post(token, Map.of("jsonrpc", "2.0", "id", REQUEST_ID.incrementAndGet(), "method", method, "params", params));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        return rpc(mcpToken(user), "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private JsonNode ok(final String user, final String tool, final Map<String, Object> arguments) {
        final JsonNode response = call(user, tool, arguments);
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return JSON.readTree(result.get("content").get(0).get("text").asString());
    }

    @Test
    void theToolsOnlyRead() {
        final List<String> names = new ArrayList<>();

        for (final JsonNode tool : rpc(mcpToken(ADMIN), "tools/list", Map.of()).path("result").path("tools")) {
            final String name = tool.get("name").asString();

            names.add(name);
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(false)).as(name).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(name).isFalse();
        }

        assertThat(names).containsExactlyInAnyOrder("search_catalog", "list_albums", "get_album", "get_track", "list_spex", "list_top_tracks")
                .noneMatch(name -> name.contains("audio") || name.contains("stream") || name.contains("download") || name.contains("cover")
                        || name.contains("upload") || name.contains("password") || name.contains("subsonic") || name.contains("setting")
                        || name.contains("maintenance") || name.contains("create") || name.contains("update") || name.contains("delete"));
    }

    @Test
    void listenersSearchAndReadPublishedMusic() throws Exception {
        final String albumId = createAlbum();
        final String trackId = createTrack(albumId);

        graphQl(EDITOR)
                .document("mutation($id: ID!) { trackUpdate(id: $id, input: {discNumber: 1, trackNumber: 1, title: \"Ouvertyr\", performers: \"Kören, Kungen\", melody: \"Dancing Queen (ABBA)\", lyrics: \"Hej hopp\", lyricsSynced: false}) { id } }")
                .variable("id", trackId)
                .execute().errors().verify();

        // Unpublished: only editors see it
        assertThat(ok(LISTENER, "search_catalog", args("query", "kören")).get("tracks")).isEmpty();
        assertThat(refused(call(LISTENER, "get_album", args("albumId", albumId)))).isTrue();
        assertThat(ok(EDITOR, "get_album", args("albumId", albumId)).get("published").asBoolean()).isFalse();
        assertThat(ok(EDITOR, "list_albums", args("includeUnpublished", true)).get("items")).hasSize(1);
        assertThat(ok(LISTENER, "list_albums", args("includeUnpublished", true)).get("items")).isEmpty();

        publish(albumId);

        final JsonNode found = ok(LISTENER, "search_catalog", args("query", "kören"));

        assertThat(found.get("tracks")).hasSize(1);
        assertThat(found.get("tracks").get(0).get("id").asString()).isEqualTo(trackId);
        assertThat(found.get("tracks").get(0).get("year").asInt()).isEqualTo(2024);
        assertThat(found.get("tracks").get(0).get("spex").asString()).isEqualTo("Cleopatra");
        assertThat(ok(LISTENER, "search_catalog", args("query", "cleopatra")).get("albums").get(0).get("id").asString()).isEqualTo(albumId);

        final JsonNode album = ok(LISTENER, "get_album", args("albumId", albumId));

        assertThat(album.get("title").asString()).isEqualTo("Cleopatra");
        assertThat(album.get("spex").get(0).get("id").asString()).isEqualTo(SPEX_ID);
        assertThat(album.get("tracks").get(0).get("performers").asString()).isEqualTo("Kören, Kungen");
        assertThat(album.get("tracks").get(0).get("duration").asString()).isEqualTo("0:02");

        final JsonNode track = ok(LISTENER, "get_track", args("trackId", trackId));

        assertThat(track.get("track").get("melody").asString()).isEqualTo("Dancing Queen (ABBA)");
        assertThat(track.get("lyrics").asString()).isEqualTo("Hej hopp");
        assertThat(track.get("spex").get(0).get("title").asString()).isEqualTo("Cleopatra");
        assertThat(track.toString()).doesNotContain("navidrome", "/media/", "/api/tracks");

        assertThat(ok(LISTENER, "list_albums", args("spexId", SPEX_ID, "year", 2024)).get("items").get(0).get("trackCount").asInt()).isEqualTo(1);
        assertThat(ok(LISTENER, "list_albums", args("year", 1999)).get("items")).isEmpty();

        final JsonNode spex = ok(LISTENER, "list_spex", args()).get("items");

        assertThat(spex).hasSize(1);
        assertThat(spex.get(0).get("spex").get("title").asString()).isEqualTo("Cleopatra");
        assertThat(spex.get(0).get("albumCount").asInt()).isEqualTo(1);
        assertThat(ok(LISTENER, "list_spex", args("query", "nobel")).get("items")).isEmpty();

        // Nobody has played anything yet
        assertThat(ok(LISTENER, "list_top_tracks", args("by", "PLAYED")).get("items")).isEmpty();
        assertThat(refused(call(LISTENER, "get_track", args("trackId", "not-an-id")))).isTrue();
    }

    @Test
    void withoutUserEveryToolIsRefused() {
        final JsonNode response = call(NO_ACCESS, "list_albums", args());

        assertThat(refused(response)).as(response.toString()).isTrue();
        assertThat(response.toString()).contains("FORBIDDEN");
        assertThat(refused(call(NO_ACCESS, "search_catalog", args("query", "cleopatra")))).isTrue();
    }

    @Test
    void tokensFromOtherClientsAreRefused() {
        for (final String token : List.of(token(LISTENER), mcpToken(LISTENER, UNBOUND_MCP_CLIENT_ID), spexflixServiceToken())) {
            assertThat(post(token, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list")).status()).isEqualTo(401);
        }
    }

    @Test
    void mcpTokensAreRefusedByTheRestOfTheApi() {
        final int status = http().post().uri("/api/graphql")
                .header(HttpHeaders.AUTHORIZATION, mcpToken(LISTENER))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"query\": \"{ albums { id } }\"}")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }

    @Test
    void noTokenIsRefusedWithResourceMetadata() {
        final Response response = post(null, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.wwwAuthenticate()).contains("resource_metadata");
    }

    @Test
    void theProtectedResourceMetadataIsServed() {
        final String body = http().get().uri("/.well-known/oauth-protected-resource/api/mcp").retrieve().body(String.class);

        assertThat(body).contains("/api/mcp").contains("authorization_servers");
    }

    private String createAlbum() {
        return graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id } }")
                .execute()
                .path("albumCreate.id").entity(String.class).get();
    }

    private String createTrack(final String albumId) throws Exception {
        final String uploadId = upload(EDITOR, flac(folder, 2));

        return graphQl(EDITOR)
                .document("mutation($album: ID!, $upload: String!) { trackCreate(albumId: $album, uploadId: $upload) { id } }")
                .variables(Map.of("album", albumId, "upload", uploadId))
                .execute()
                .path("trackCreate.id").entity(String.class).get();
    }

    private void publish(final String albumId) {
        graphQl(EDITOR)
                .document("mutation($id: ID!) { albumPublish(id: $id, published: true) { published } }")
                .variable("id", albumId)
                .execute()
                .path("albumPublish.published").entity(Boolean.class).isEqualTo(true);
    }

    private record Response(int status, @Nullable String wwwAuthenticate, String body) {
    }
}
