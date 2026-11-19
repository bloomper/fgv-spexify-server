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


package nu.fgv.spexify.server.sheetmusic;

import nu.fgv.spexify.server.AbstractIntegrationTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sheet music from Spexarkivet beside the recordings, the maps that make the cursor follow it, and the lyrics' history
 * and singers.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SheetMusicIntegrationTest extends AbstractIntegrationTest {

    private static final String PDF_ID = "00000000-0000-7000-8000-00000000f001";
    private static final String XML_ID = "00000000-0000-7000-8000-00000000f002";
    private static final String SCORE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
            <score-partwise><part-list><score-part id="P1"><part-name>Sång</part-name></score-part></part-list><part id="P1">
            <measure number="1"><attributes><divisions>1</divisions></attributes><sound tempo="120"/>
            <note><pitch><step>A</step><octave>4</octave></pitch><duration>4</duration></note></measure>
            <measure number="2"><note><pitch><step>A</step><octave>4</octave></pitch><duration>4</duration></note></measure>
            </part></score-partwise>""";

    @TempDir
    private Path folder;

    private String trackId;

    private static String file(final String id, final String title, final String extension, final String contentType) {
        return """
                {"id": "%s", "title": "%s", "name": "%s.%s", "extension": "%s", "contentType": "%s", "size": 100, "checksum": "%s",
                 "pages": null, "roleSv": "Partitur", "roleEn": "Score", "instrumentsSv": [], "instrumentsEn": [], "desk": null}"""
                .formatted(id, title, title, extension, extension, contentType, "a".repeat(64));
    }

    @BeforeEach
    void publishATrackOfANumberWithSheetMusic() throws Exception {
        final String albumId = graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"00000000-0000-7000-8000-000000000001\"]}) { id } }")
                .execute()
                .path("albumCreate.id").entity(String.class).get();

        trackId = graphQl(EDITOR)
                .document("mutation($album: ID!, $upload: String!) { trackCreate(albumId: $album, uploadId: $upload) { id } }")
                .variables(Map.of("album", albumId, "upload", upload(EDITOR, flac(folder, 6))))
                .execute()
                .path("trackCreate.id").entity(String.class).get();
        graphQl(EDITOR).document("mutation($id: ID!, $number: ID) { trackSetNumber(id: $id, numberId: $number) { numberId } }")
                .variables(Map.of("id", trackId, "number", NUMBER_ID)).execute();
        graphQl(EDITOR).document("mutation($id: ID!) { albumPublish(id: $id, published: true) { id } }").variable("id", albumId).execute();

        ARCHIVE.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SheetMusic"))
                .atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spexifySheetMusic": [{"arrangementId": "00000000-0000-7000-8000-00000000e001", "numberId": "%s",
                          "versions": [{"id": "00000000-0000-7000-8000-00000000e002", "label": "Original", "arranger": "A. Arrangör",
                                        "recommended": true, "keyTonic": "F_SHARP", "keyMode": "MINOR", "tempo": null,
                                        "files": [%s, %s]}]}]}}""".formatted(NUMBER_ID,
                        file(PDF_ID, "Partitur", "pdf", "application/pdf"),
                        file(XML_ID, "Partitur", "musicxml", "application/vnd.recordare.musicxml+xml")))));
        ARCHIVE.stubFor(get(urlEqualTo("/api/files/" + PDF_ID))
                .willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF-1.4 sheet music")));
        ARCHIVE.stubFor(get(urlEqualTo("/api/files/" + XML_ID))
                .willReturn(aResponse().withHeader("Content-Type", "application/vnd.recordare.musicxml+xml").withBody(SCORE)));
    }

    private ResponseEntity<byte[]> sheetFile(final String user, final String fileId) {
        return http().get()
                .uri("/api/tracks/{track}/sheet-music/{file}", trackId, fileId)
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .retrieve()
                .onStatus(_ -> true, (_, _) -> {
                })
                .toEntity(byte[].class);
    }

    @Test
    void usersWithAccessToTheArchiveSeeAndOpenTheSheetMusic() {
        final String query = "query($id: ID!) { album(id: $id) { tracks { sheetMusic { archiveUrl versions { key files { id kind roleSv } } } } } }";
        final String albumId = graphQl(EDITOR).document("query($q: String!) { search(q: $q) { tracks { albumId } } }")
                .variable("q", "Ouvertyr").execute().path("search.tracks[0].albumId").entity(String.class).get();

        graphQl(BOTH).document(query).variable("id", albumId).execute()
                .path("album.tracks[0].sheetMusic.archiveUrl").entity(String.class).isEqualTo("https://arkiv.test/arrangements/00000000-0000-7000-8000-00000000e001")
                .path("album.tracks[0].sheetMusic.versions[0].key").entity(String.class).isEqualTo("fiss-moll")
                .path("album.tracks[0].sheetMusic.versions[0].files[*].kind").entityList(String.class).containsExactly("PDF", "MUSIC_XML");
        graphQl(LISTENER).document(query).variable("id", albumId).execute()
                .path("album.tracks[0].sheetMusic").valueIsNull();

        final ResponseEntity<byte[]> pdf = sheetFile(BOTH, PDF_ID);

        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(pdf.getBody(), StandardCharsets.UTF_8)).startsWith("%PDF");
        assertThat(pdf.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
        assertThat(sheetFile(LISTENER, PDF_ID).getStatusCode().value()).isEqualTo(403);
        assertThat(sheetFile(BOTH, "00000000-0000-7000-8000-00000000ffff").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anEditorSyncsTheScoreByHandAndAutomatically() {
        final String syncs = "query($q: String!) { search(q: $q) { tracks { scoreSyncs { fileId method status points { seconds measure beat } quality version } } } }";

        graphQl(ARCHIVE_EDITOR).document("""
                        mutation($track: ID!, $file: ID!) { scoreSyncSave(trackId: $track, fileId: $file,
                            input: {points: [{seconds: 0.5, measure: 0, beat: 0}, {seconds: 2.5, measure: 1, beat: 0}]}) { method status } }""")
                .variables(Map.of("track", trackId, "file", XML_ID)).execute()
                .path("scoreSyncSave.status").entity(String.class).isEqualTo("DONE");
        graphQl(BOTH).document(syncs).variable("q", "Ouvertyr").execute()
                .path("search.tracks[0].scoreSyncs[0].method").entity(String.class).isEqualTo("MANUAL")
                .path("search.tracks[0].scoreSyncs[0].points[1].measure").entity(Integer.class).isEqualTo(1);

        // Saving over someone else's map needs its version
        expectError(graphQl(ARCHIVE_EDITOR).document("""
                        mutation($track: ID!, $file: ID!) { scoreSyncSave(trackId: $track, fileId: $file,
                            input: {points: [{seconds: 1, measure: 0, beat: 0}]}) { method } }""")
                .variables(Map.of("track", trackId, "file", XML_ID)).execute(), "CONFLICT");
        // Without access to the archive an editor cannot, nor can anyone sync a PDF
        expectError(graphQl(EDITOR).document("mutation($track: ID!, $file: ID!) { scoreAlign(trackId: $track, fileId: $file) { status } }")
                .variables(Map.of("track", trackId, "file", XML_ID)).execute(), "FORBIDDEN");
        expectError(graphQl(ARCHIVE_EDITOR).document("mutation($track: ID!, $file: ID!) { scoreAlign(trackId: $track, fileId: $file) { status } }")
                .variables(Map.of("track", trackId, "file", PDF_ID)).execute(), "BAD_REQUEST");

        graphQl(ARCHIVE_EDITOR).document("mutation($track: ID!, $file: ID!) { scoreAlign(trackId: $track, fileId: $file) { method status } }")
                .variables(Map.of("track", trackId, "file", XML_ID)).execute()
                .path("scoreAlign.method").entity(String.class).isEqualTo("AUTOMATIC");

        final Instant until = Instant.now().plus(Duration.ofSeconds(30));
        String status = "PENDING";

        while (("PENDING".equals(status) || "RUNNING".equals(status)) && Instant.now().isBefore(until)) {
            status = jdbcClient.sql("SELECT status FROM score_sync WHERE method = 'AUTOMATIC'").query(String.class).single();
        }
        assertThat(status).isEqualTo("DONE");
        graphQl(ARCHIVE_EDITOR).document(syncs).variable("q", "Ouvertyr").execute()
                .path("search.tracks[0].scoreSyncs[*].method").entityList(String.class).containsExactly("MANUAL", "AUTOMATIC");
        // The listener gets the manual one only
        graphQl(BOTH).document(syncs).variable("q", "Ouvertyr").execute()
                .path("search.tracks[0].scoreSyncs[*].method").entityList(String.class).containsExactly("MANUAL");
        graphQl(ARCHIVE_EDITOR).document("mutation($track: ID!, $file: ID!) { scoreSyncDelete(trackId: $track, fileId: $file) }")
                .variables(Map.of("track", trackId, "file", XML_ID)).execute()
                .path("scoreSyncDelete").entity(Boolean.class).isEqualTo(true);
        graphQl(BOTH).document(syncs).variable("q", "Ouvertyr").execute()
                .path("search.tracks[0].scoreSyncs[*].method").entityList(String.class).containsExactly("AUTOMATIC");
    }

    private String saveLyrics(final String lyrics, final @Nullable String base) {
        final Map<String, Object> variables = new java.util.HashMap<>(Map.of("id", trackId, "lyrics", lyrics));

        variables.put("base", base);
        return graphQl(EDITOR).document("""
                        mutation($id: ID!, $lyrics: String, $base: ID) { trackLyricsUpdate(id: $id, lyrics: $lyrics, synced: true, baseRevisionId: $base) {
                            lyrics lyricsRevisions { id } } }""")
                .variables(variables).execute()
                .path("trackLyricsUpdate.lyricsRevisions[0].id").entity(String.class).get();
    }

    @Test
    void lyricsKeepTheirHistoryAndNameTheirSingers() {
        SPEXIPEDIA.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SpexCharacters"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spex": {"characters": [{"id": "1", "name": "Philip", "retired": false},
                                                          {"id": "2", "name": "Pamela", "retired": false}]}}}""")));

        final String first = saveLyrics("[00:01.00]Ph: Här står jag\n[00:03.00]Ph/Pa: och här står vi", null);

        expectError(graphQl(EDITOR).document("mutation($id: ID!) { trackLyricsUpdate(id: $id, lyrics: \"x\", synced: false) { id } }")
                .variable("id", trackId).execute(), "CONFLICT");

        final String second = saveLyrics("[00:01.50]Ph: Här står jag", first);

        graphQl(EDITOR).document("query($q: String!) { search(q: $q) { tracks { lyricsRevisions { id lyrics createdBy } singers { label name } waveform(points: 50) } } }")
                .variable("q", "Ouvertyr").execute()
                .path("search.tracks[0].lyricsRevisions[*].id").entityList(String.class).containsExactly(second, first)
                .path("search.tracks[0].lyricsRevisions[0].createdBy").entity(String.class).isEqualTo("Erik Test")
                .path("search.tracks[0].singers[0].name").entity(String.class).isEqualTo("Philip")
                .path("search.tracks[0].waveform").entityList(Double.class).hasSize(50);
        graphQl(EDITOR).document("mutation($id: ID!, $revision: ID!) { trackLyricsRestore(id: $id, revisionId: $revision) { lyrics lyricsRevisions { id } } }")
                .variables(Map.of("id", trackId, "revision", first)).execute()
                .path("trackLyricsRestore.lyrics").entity(String.class).isEqualTo("[00:01.00]Ph: Här står jag\n[00:03.00]Ph/Pa: och här står vi")
                .path("trackLyricsRestore.lyricsRevisions").entityList(Object.class).hasSize(3);
        // Listeners see neither the history nor the waveform
        graphQl(LISTENER).document("query($q: String!) { search(q: $q) { tracks { lyricsRevisions { id } singers { label name } } } }")
                .variable("q", "Ouvertyr").execute()
                .path("search.tracks[0].lyricsRevisions").entityList(Object.class).hasSize(0)
                .path("search.tracks[0].singers[*].name").entityList(String.class).containsExactly("Philip", "Philip & Pamela");
        assertThat(List.of(first, second)).doesNotHaveDuplicates();
    }
}
