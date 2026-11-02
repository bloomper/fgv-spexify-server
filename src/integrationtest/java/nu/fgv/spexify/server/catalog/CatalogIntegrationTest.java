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

package nu.fgv.spexify.server.catalog;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalogue from upload to listening: an editor creates an album on a spex, uploads a track with tus and publishes
 * it, and a listener plays and downloads it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class CatalogIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    private Path folder;

    @Test
    void anAlbumGoesFromUploadToListeners() throws Exception {
        final String albumId = createAlbum();
        final String trackId = createTrack(albumId);

        graphQl(LISTENER).document("{ albums { id } }").execute()
                .path("albums").entityList(Object.class).hasSize(0);

        publish(albumId);

        graphQl(LISTENER)
                .document("query($id: ID!) { album(id: $id) { title year published spex { title } tracks { id title listed format } } }")
                .variable("id", albumId)
                .execute()
                .path("album.title").entity(String.class).isEqualTo("Cleopatra")
                .path("album.year").entity(Integer.class).isEqualTo(2024)
                .path("album.spex[0].title").entity(String.class).isEqualTo("Cleopatra")
                .path("album.tracks[0].id").entity(String.class).isEqualTo(trackId)
                .path("album.tracks[0].title").entity(String.class).isEqualTo("Ouvertyr")
                .path("album.tracks[0].listed").entity(Boolean.class).isEqualTo(true)
                .path("album.tracks[0].format").entity(String.class).isEqualTo("FLAC");

        try (var files = Files.walk(MEDIA.resolve("music"))) {
            assertThat(files.filter(Files::isRegularFile).map(Path::toString)).anyMatch(name -> name.endsWith(".flac"));
        }

        final ResponseEntity<byte[]> range = http().get()
                .uri("/api/tracks/{id}/audio", trackId)
                .header(HttpHeaders.AUTHORIZATION, token(LISTENER))
                .header(HttpHeaders.RANGE, "bytes=0-3")
                .retrieve()
                .toEntity(byte[].class);

        assertThat(range.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(range.getBody()).containsExactly('f', 'L', 'a', 'C');

        final ResponseEntity<byte[]> zip = http().get()
                .uri("/api/albums/{id}/download", albumId)
                .header(HttpHeaders.AUTHORIZATION, token(LISTENER))
                .retrieve()
                .toEntity(byte[].class);

        assertThat(zip.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(zip.getBody()).startsWith('P', 'K');
    }

    @Test
    void listenersGetReplayGainCreditsAndRadio() throws Exception {
        final String albumId = createAlbum();
        final String first = createTrack(albumId);
        final String second = createTrack(albumId);

        graphQl(EDITOR)
                .document("mutation($id: ID!) { trackUpdate(id: $id, input: {discNumber: 1, trackNumber: 1, title: \"Ouvertyr\", performers: \"Kören, Kungen\", melody: \"Dancing Queen (ABBA)\", lyricsSynced: false}) { id } }")
                .variable("id", first)
                .execute().errors().verify();
        publish(albumId);

        graphQl(LISTENER)
                .document("query($id: ID!) { album(id: $id) { tracks { replayGain { trackGain trackPeak albumGain albumPeak } } } }")
                .variable("id", albumId)
                .execute()
                .path("album.tracks[0].replayGain.trackGain").entity(Double.class).satisfies(gain -> assertThat(gain).isBetween(0.0, 6.0))
                .path("album.tracks[0].replayGain.trackPeak").entity(Double.class).satisfies(peak -> assertThat(peak).isBetween(0.05, 0.2))
                .path("album.tracks[0].replayGain.albumGain").hasValue();
        try (var files = Files.walk(MEDIA.resolve("music"))) {
            final Path file = files.filter(path -> path.toString().endsWith(".flac")).findFirst().orElseThrow();

            assertThat(ffmpeg.probe(List.of("-v", "error", "-show_entries", "format_tags", "-of", "json", file.toString())))
                    .containsIgnoringCase("replaygain_track_gain");
        }

        graphQl(LISTENER)
                .document("query($id: ID!) { credits(kind: PERFORMER) { name trackCount } artist: credits(kind: ORIGINAL_ARTIST) { name } creditTracks(kind: ORIGINAL_ARTIST, name: \"abba\") { id } radio(trackId: $id) { id } }")
                .variable("id", first)
                .execute()
                .path("credits[*].name").entityList(String.class).containsExactly("Kungen", "Kören")
                .path("artist[*].name").entityList(String.class).containsExactly("ABBA")
                .path("creditTracks[*].id").entityList(String.class).containsExactly(first)
                .path("radio[*].id").entityList(String.class).containsExactly(first, second);
    }

    @Test
    void unpublishedTracksStayWithEditors() throws Exception {
        final String albumId = createAlbum();
        final String trackId = createTrack(albumId);

        publish(albumId);
        graphQl(EDITOR)
                .document("mutation($id: ID!) { trackPublish(id: $id, published: false) { listed } }")
                .variable("id", trackId)
                .execute()
                .path("trackPublish.listed").entity(Boolean.class).isEqualTo(false);

        graphQl(LISTENER)
                .document("query($id: ID!) { album(id: $id) { tracks { id } } }")
                .variable("id", albumId)
                .execute()
                .path("album.tracks").entityList(Object.class).hasSize(0);
        final HttpStatusCode status = http().get()
                .uri("/api/tracks/{id}/audio", trackId)
                .header(HttpHeaders.AUTHORIZATION, token(LISTENER))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void trashedAlbumsAreDeletedOnlyByAdmins() throws Exception {
        final String albumId = createAlbum();

        createTrack(albumId);
        publish(albumId);
        graphQl(EDITOR).document("mutation($id: ID!) { albumTrash(id: $id) { trashedAt } }").variable("id", albumId).execute()
                .path("albumTrash.trashedAt").hasValue();

        graphQl(LISTENER).document("{ albums { id } }").execute().path("albums").entityList(Object.class).hasSize(0);
        graphQl(EDITOR).document("{ trash { albums { id } } }").execute()
                .path("trash.albums[0].id").entity(String.class).isEqualTo(albumId);

        expectError(graphQl(EDITOR).document("mutation($id: ID!) { albumDelete(id: $id) }").variable("id", albumId).execute(), "FORBIDDEN");
        graphQl(ADMIN).document("mutation($id: ID!) { albumDelete(id: $id) }").variable("id", albumId).execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album").query(Long.class).single()).isZero();
    }

    @Test
    void anAlbumNeedsSpexregisterOnlyWhenItIsCreated() throws Exception {
        final String albumId = createAlbum();

        spexregisterDown();
        graphQl(EDITOR)
                .document("query($id: ID!) { album(id: $id) { title spex { title } } }")
                .variable("id", albumId)
                .execute()
                .path("album.spex[0].title").entity(String.class).isEqualTo("Cleopatra");
        // A spex that is not cached yet
        expectError(graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000002\"]}) { id } }")
                .execute(), "SPEXREGISTER_UNAVAILABLE");
    }

    @Test
    void changesAreAudited() throws Exception {
        final String albumId = createAlbum();

        publish(albumId);
        graphQl(EDITOR)
                .document("{ auditLog { totalCount entries { action entityType actorName } } }")
                .execute()
                .path("auditLog.entries[*].action").entityList(String.class).contains("CREATE", "PUBLISH");
        graphQl(LISTENER).document("{ auditLog { totalCount } }").execute().errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());

        // Narrowed by action, record, text and period; the editor sees only their own changes and only themselves as actor
        graphQl(EDITOR)
                .document("query($id: ID!) { published: auditLog(filter: {action: \"PUBLISH\"}) { totalCount entries { entityId actorId } } "
                        + "album: auditLog(filter: {entityType: \"ALBUM\", entityId: $id}) { totalCount } "
                        + "text: auditLog(filter: {q: \"PUBLICERADE\"}) { totalCount } "
                        + "future: auditLog(filter: {from: \"2999-01-01T00:00:00Z\"}) { totalCount } "
                        + "auditActors { id name } }")
                .variable("id", albumId)
                .execute()
                .path("published.totalCount").entity(Integer.class).isEqualTo(1)
                .path("published.entries[0].entityId").entity(String.class).isEqualTo(albumId)
                .path("album.totalCount").entity(Integer.class).isEqualTo(2)
                .path("text.totalCount").entity(Integer.class).isEqualTo(1)
                .path("future.totalCount").entity(Integer.class).isEqualTo(0)
                .path("auditActors").entityList(Object.class).hasSize(1);
        graphQl(ADMIN).document("{ auditLog(filter: {actorId: \"nobody\"}) { totalCount } }").execute()
                .path("auditLog.totalCount").entity(Integer.class).isEqualTo(0);
    }

    private String createAlbum() {
        return graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id published } }")
                .execute()
                .path("albumCreate.published").entity(Boolean.class).isEqualTo(false)
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
}
