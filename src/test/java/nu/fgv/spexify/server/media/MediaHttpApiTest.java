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

package nu.fgv.spexify.server.media;

import nu.fgv.spexify.server.catalog.Album;
import nu.fgv.spexify.server.catalog.AlbumKindService;
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.spex.SpexService;
import nu.fgv.spexify.server.user.Caller;
import nu.fgv.spexify.server.user.Role;
import nu.fgv.spexify.server.util.AbstractApiTest;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.restdocs.request.PathParametersSnippet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static nu.fgv.spexify.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.multipart;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.relaxedResponseFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.partWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.requestParts;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents the audio, download and cover endpoints.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(MediaController.class)
@Import(MediaHttpApiTest.Signer.class)
class MediaHttpApiTest extends AbstractApiTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final MediaLinkSigner SIGNER = new MediaLinkSigner("https://api.spexify.fgv.local.nu:8443", "test-signing-key",
            Duration.ofHours(6), Clock.fixed(NOW, ZoneOffset.UTC));

    private static final byte[] AUDIO = {'f', 'L', 'a', 'C', 0, 0, 0, 34};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    private final PathParametersSnippet trackId = pathParameters(parameterWithName("id").description("The id of the track"));
    private final PathParametersSnippet albumId = pathParameters(parameterWithName("id").description("The id of the album"));

    private final ResponseFieldsSnippet linkFields = responseFields(
            fieldWithPath("url").description("The signed link, on the API's public address; it needs no access token"),
            fieldWithPath("expiresAt").description("When the link stops working, after which it answers `410 Gone`")
    );

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private MediaStorage storage;

    @MockitoBean
    private SpexService spexService;

    @TempDir
    private Path folder;

    private Album album;
    private Track track;

    /**
     * The path of a signed link, without the public address.
     */
    private static String signedPath(final String scope) {
        return SIGNER.sign(scope).url().replace("https://api.spexify.fgv.local.nu:8443", "");
    }

    private static AlbumDto albumDto(final boolean hasCover) {
        return new AlbumDto(uuid(1), "Cleopatra", 2024, new AlbumKindService.AlbumKindDto(uuid(101), "Föreställning", "Show", 1), null, null,
                hasCover, true, Instant.parse("2026-10-01T10:00:00Z"), null, null, 12, 2_400_000, Instant.parse("2026-09-30T10:00:00Z"), null);
    }

    @BeforeEach
    void setUpCatalog() throws IOException {
        album = new Album();
        album.setId(1L);
        album.setUuid(uuid(1));
        album.setTitle("Cleopatra");
        album.setYear(2024);
        album.setCoverExtension("png");
        track = new Track();
        track.setId(2L);
        track.setUuid(uuid(2));
        track.setAlbum(album);
        track.setTrackNumber(1);
        track.setTitle("Ouvertyr");
        track.setFileExtension("flac");
        album.getTracks().add(track);

        final Path audio = Files.write(folder.resolve("track.flac"), AUDIO);
        final Path cover = Files.write(folder.resolve("cover.png"), PNG);

        when(storage.trackFile(track)).thenReturn(audio);
        when(storage.exists(track)).thenReturn(true);
        when(storage.coverFile(album)).thenReturn(cover);
        when(callerService.current()).thenReturn(new Caller("subject", "user@fgv.nu", "User", Set.of(Role.USER), false));
    }

    @Test
    void streamsATrack() throws Exception {
        when(catalogService.playableTrack(uuid(2))).thenReturn(track);

        mockMvc
                .perform(get("/api/tracks/{id}/audio", uuid(2)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/flac"))
                .andExpect(content().bytes(AUDIO))
                .andDo(document(
                        "track-audio-get",
                        trackId,
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The audio's type, e.g. `audio/flac` or `audio/mpeg`"),
                                headerWithName(HttpHeaders.ACCEPT_RANGES).description("`bytes`: a `Range` header fetches part of the file"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for an hour")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void downloadsATrack() throws Exception {
        when(catalogService.playableTrack(uuid(2))).thenReturn(track);

        mockMvc
                .perform(get("/api/tracks/{id}/download", uuid(2)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().bytes(AUDIO))
                .andDo(document(
                        "track-download-get",
                        trackId,
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The audio's type"),
                                headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("An attachment, named by the track's number and title")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void downloadsAnAlbumAsZip() throws Exception {
        when(catalogService.visibleAlbumWithTracks(uuid(1))).thenReturn(album);

        final MvcResult started = mockMvc
                .perform(get("/api/albums/{id}/download", uuid(1)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc
                .perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andDo(document(
                        "album-download-get",
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("`application/zip`"),
                                headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("An attachment, named by the album's title and year")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void servesAnAlbumsCover() throws Exception {
        when(catalogService.visibleAlbum(uuid(1))).thenReturn(album);

        mockMvc
                .perform(get("/api/albums/{id}/cover", uuid(1)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andDo(document(
                        "album-cover-get",
                        albumId,
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The image's type"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for an hour")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void uploadsAnAlbumsCover() throws Exception {
        when(catalogService.albumCover(eq(uuid(1)), any(), eq("png"))).thenReturn(albumDto(true));

        mockMvc
                .perform(multipart("/api/albums/{id}/cover", uuid(1))
                        .file(new MockMultipartFile("file", "cover.png", "image/png", PNG))
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCover").value(true))
                .andDo(document(
                        "album-cover-post",
                        preprocessResponse(prettyPrint()),
                        albumId,
                        requestParts(partWithName("file").description("A JPEG, PNG or WebP image of at most 10 MB")),
                        secureRequestHeaders,
                        relaxedResponseFields(
                                fieldWithPath("id").description("The id of the album"),
                                fieldWithPath("title").description("The title of the album"),
                                fieldWithPath("hasCover").description("Whether the album has a cover of its own")
                        ),
                        security(List.of("USER", "EDITOR"))
                ));
    }

    @Test
    void aTrackTheCallerMayNotPlayIsNotFound() throws Exception {
        when(catalogService.playableTrack(uuid(3))).thenThrow(new ResourceNotFoundException(Track.class, uuid(3)));

        mockMvc
                .perform(get("/api/tracks/{id}/audio", uuid(3)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andDo(document(
                        "error",
                        preprocessResponse(prettyPrint()),
                        errorResponseFields
                ));
    }

    @Test
    void withoutItsOwnCoverAnAlbumShowsTheSpexPosterOrNothing() throws Exception {
        final Album withSpex = new Album();

        withSpex.setId(1L);
        withSpex.setTitle("Cleopatra");
        final nu.fgv.spexify.server.catalog.SpexRef spex = new nu.fgv.spexify.server.catalog.SpexRef();

        spex.setId(uuid(7));
        withSpex.getSpex().add(spex);
        when(catalogService.visibleAlbum(uuid(1))).thenReturn(withSpex);
        when(spexService.poster(uuid(7))).thenReturn(java.util.Optional.of(new nu.fgv.spexify.server.spex.SpexregisterClient.Poster(PNG, "image/png")));
        mockMvc.perform(get("/api/albums/{id}/cover", uuid(1))).andExpect(status().isOk()).andExpect(content().bytes(PNG));

        when(spexService.poster(uuid(7))).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/albums/{id}/cover", uuid(1))).andExpect(status().isNotFound());

        final Album withoutSpex = new Album();

        withoutSpex.setId(2L);
        withoutSpex.setTitle("Okänt");
        when(catalogService.visibleAlbum(uuid(2))).thenReturn(withoutSpex);
        mockMvc.perform(get("/api/albums/{id}/cover", uuid(2))).andExpect(status().isNotFound());
        when(catalogService.visibleAlbum(uuid(3))).thenThrow(new ResourceNotFoundException("error.album.notFound", uuid(3)));
        mockMvc.perform(get("/api/albums/{id}/cover", uuid(3))).andExpect(status().isNotFound());
    }

    @Test
    void aCoverThatIsNotAnImageIsRefused() throws Exception {
        mockMvc.perform(multipart("/api/albums/{id}/cover", uuid(1)).file(new MockMultipartFile("file", "cover.png", "image/png", "<svg/>".getBytes())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/albums/{id}/cover", uuid(1)).file(new MockMultipartFile("file", "cover.png", "image/png", new byte[0])))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signsALinkToATracksAudio() throws Exception {
        when(catalogService.playableTrack(uuid(2))).thenReturn(track);

        mockMvc
                .perform(get("/api/tracks/{id}/audio/link", uuid(2)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(SIGNER.sign("tracks/" + uuid(2) + "/audio").url()))
                .andDo(document(
                        "track-audio-link-get",
                        preprocessResponse(prettyPrint()),
                        trackId,
                        secureRequestHeaders,
                        linkFields,
                        security(List.of("USER"))
                ));
        mockMvc.perform(get("/api/tracks/{id}/download/link", uuid(2)))
                .andExpect(jsonPath("$.url").value(SIGNER.sign("tracks/" + uuid(2) + "/download").url()));
    }

    @Test
    void anEditorsAlbumLinkHoldsEveryTrack() throws Exception {
        when(catalogService.visibleAlbum(uuid(1))).thenReturn(album);

        mockMvc
                .perform(get("/api/albums/{id}/download/link", uuid(1)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(SIGNER.sign("albums/" + uuid(1) + "/download").url()))
                .andDo(document(
                        "album-download-link-get",
                        preprocessResponse(prettyPrint()),
                        albumId,
                        secureRequestHeaders,
                        linkFields,
                        security(List.of("USER"))
                ));

        when(callerService.current()).thenReturn(new Caller("subject", "editor@fgv.nu", "Editor", Set.of(Role.USER, Role.EDITOR), false));
        mockMvc.perform(get("/api/albums/{id}/download/link", uuid(1)))
                .andExpect(jsonPath("$.url").value(SIGNER.sign("albums/" + uuid(1) + "/download/all").url()));
    }

    @Test
    void streamsATrackFromASignedLink() throws Exception {
        when(catalogService.linkedTrack(uuid(2))).thenReturn(track);

        mockMvc
                .perform(get(signedPath("tracks/" + uuid(2) + "/audio")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/flac"))
                .andExpect(content().bytes(AUDIO))
                .andDo(document(
                        "track-audio-signed-get",
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The audio's type, e.g. `audio/flac` or `audio/mpeg`"),
                                headerWithName(HttpHeaders.ACCEPT_RANGES).description("`bytes`: a `Range` header fetches part of the file"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for an hour")
                        ),
                        noSecurity()
                ));
        mockMvc.perform(get(signedPath("tracks/" + uuid(2) + "/download")))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void aSignedAlbumLinkHoldsTheListedTracksUnlessSignedForAnEditor() throws Exception {
        final Track unlisted = new Track();

        unlisted.setId(3L);
        unlisted.setAlbum(album);
        unlisted.setTrackNumber(2);
        unlisted.setTitle("Finalen");
        unlisted.setFileExtension("flac");
        unlisted.setPublished(false);
        album.setPublished(true);
        album.getTracks().add(unlisted);
        when(storage.exists(unlisted)).thenReturn(true);
        when(storage.trackFile(unlisted)).thenReturn(folder.resolve("track.flac"));
        when(catalogService.linkedAlbumWithTracks(uuid(1))).thenReturn(album);

        assertThat(zipEntries(signedPath("albums/" + uuid(1) + "/download"))).isEqualTo(1);
        assertThat(zipEntries(signedPath("albums/" + uuid(1) + "/download/all"))).isEqualTo(2);
    }

    private long zipEntries(final String path) throws Exception {
        final MvcResult started = mockMvc.perform(get(path)).andExpect(request().asyncStarted()).andReturn();
        final byte[] zip = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        long entries = 0;

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            while (in.getNextEntry() != null) {
                entries++;
            }
        }
        return entries;
    }

    @Test
    void aForgedOrExpiredLinkIsRefused() throws Exception {
        final String path = signedPath("tracks/" + uuid(2) + "/audio");

        // Another track with this track's signature
        mockMvc.perform(get(path.replace(uuid(2).toString(), uuid(3).toString()))).andExpect(status().isForbidden());
        // A later expiry with the same signature
        mockMvc.perform(get(path.replaceFirst("/media/(\\d+)/", "/media/" + NOW.plus(Duration.ofDays(30)).getEpochSecond() + "/")))
                .andExpect(status().isForbidden());
        // The download with the audio's signature
        mockMvc.perform(get(path.replace("/audio", "/download"))).andExpect(status().isForbidden());

        // Signed seven hours ago, so it ran out an hour ago
        final String expired = new MediaLinkSigner("", "test-signing-key", Duration.ofHours(6),
                Clock.fixed(NOW.minus(Duration.ofHours(7)), ZoneOffset.UTC)).sign("tracks/" + uuid(2) + "/audio").url();

        mockMvc.perform(get(expired)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("GONE"));
    }

    @Test
    void aTrackWhoseFileIsMissingIsNotFound() throws Exception {
        when(catalogService.playableTrack(uuid(2))).thenReturn(track);
        when(storage.trackFile(track)).thenReturn(folder.resolve("missing.flac"));

        mockMvc.perform(get("/api/tracks/{id}/audio", uuid(2))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tracks/{id}/download", uuid(2))).andExpect(status().isNotFound());
    }

    @Test
    void contentTypesFollowTheFileExtension() {
        for (final String extension : java.util.List.of("flac", "mp3", "m4a", "ogg", "opus", "wav", "xyz")) {
            track.setFileExtension(extension);
            assertThat(MediaController.contentType(track).toString()).isNotBlank();
        }
        assertThat(MediaController.imageExtension(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("jpg");
        assertThat(MediaController.imageExtension("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("webp");
    }

    @Test
    void repeatedNamesInAnAlbumZipGetANumber() {
        assertThat(MediaController.uniqueNames(List.of("01 Visa.flac", "01 Visa.flac", "01 visa.FLAC", "02 Annan.mp3")))
                .containsExactly("01 Visa.flac", "01 Visa (2).flac", "01 visa (3).FLAC", "02 Annan.mp3");
    }

    @TestConfiguration
    static class Signer {

        @Bean
        MediaLinkSigner mediaLinkSigner() {
            return SIGNER;
        }
    }
}
