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

package nu.fgv.spexify.server.spexflix;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * The links between Spexify's tracks and Spexflix's chapters, both ways: Spexflix's service account reads tracks with
 * the {@code SPEXFLIX} role, and users of both see where a track is performed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexflixLinksIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    private Path folder;

    private String trackId;

    @BeforeEach
    void publishATrack() throws Exception {
        final String albumId = graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id } }")
                .execute()
                .path("albumCreate.id").entity(String.class).get();

        trackId = graphQl(EDITOR)
                .document("mutation($album: ID!, $upload: String!) { trackCreate(albumId: $album, uploadId: $upload) { id } }")
                .variables(Map.of("album", albumId, "upload", upload(EDITOR, flac(folder, 1))))
                .execute()
                .path("trackCreate.id").entity(String.class).get();
        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: true) { id } }").variable("id", albumId).execute();
    }

    @Test
    void spexflixReadsTheTracksOfASpex() {
        graphQlWithToken(spexflixServiceToken())
                .document("{ spexTracks(spexIds: [\"00000000-0000-7000-8000-000000000001\"]) { id title spexId } }")
                .execute()
                .path("spexTracks[0].id").entity(String.class).isEqualTo(trackId)
                .path("spexTracks[0].spexId").entity(String.class).isEqualTo(SPEX_ID);
        graphQlWithToken(spexflixServiceToken())
                .document("query($ids: [ID!]!) { tracksByIds(ids: $ids) { title } }")
                .variable("ids", List.of(trackId))
                .execute()
                .path("tracksByIds[0].title").entity(String.class).isEqualTo("Ouvertyr");
    }

    @Test
    void listenersCannotListTheTracksOfASpex() {
        expectError(graphQl(LISTENER).document("{ spexTracks(spexIds: [\"00000000-0000-7000-8000-000000000001\"]) { id } }").execute(), "FORBIDDEN");
    }

    @Test
    void usersOfBothSeeWhereATrackIsPerformed() {
        SPEXFLIX.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("ChapterLinks"))
                .atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"chapterLinks": [{"trackId": "%s", "videoId": "7", "videoTitle": "Cleopatra",
                                                    "chapterTitle": "Ouvertyr", "startSeconds": 42}]}}""".formatted(trackId))));

        graphQl(BOTH)
                .document("query($q: String!) { search(q: $q) { tracks { videoLinks { videoId startSeconds } } } }")
                .variable("q", "Ouvertyr")
                .execute()
                .path("search.tracks[0].videoLinks[0].videoId").entity(String.class).isEqualTo("7")
                .path("search.tracks[0].videoLinks[0].startSeconds").entity(Integer.class).isEqualTo(42);
    }
}
