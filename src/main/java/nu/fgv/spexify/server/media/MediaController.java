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
import nu.fgv.spexify.server.catalog.CatalogDtos.AlbumDto;
import nu.fgv.spexify.server.catalog.CatalogService;
import nu.fgv.spexify.server.catalog.Track;
import nu.fgv.spexify.server.spex.SpexService;
import nu.fgv.spexify.server.user.CallerService;
import nu.fgv.spexify.server.util.error.BadRequestException;
import nu.fgv.spexify.server.util.error.LinkExpiredException;
import nu.fgv.spexify.server.util.error.ResourceNotFoundException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Audio, downloads and covers over plain HTTP, which GraphQL does not suit. Files are served from disk with byte-range
 * support, so the web player can seek. A thin adapter: {@link CatalogService} decides access.
 *
 * <p>Audio and downloads are also handed out as signed links ({@link MediaLinkSigner}) under {@code /media/}, which
 * need no token, so that the web's player and downloads fetch them from the API's public address directly.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class MediaController {

    private static final long MAX_COVER = 10L * 1024 * 1024;

    private final CatalogService catalogService;
    private final MediaStorage storage;
    private final SpexService spexService;
    private final CallerService callerService;
    private final MediaLinkSigner signer;

    public MediaController(final CatalogService catalogService, final MediaStorage storage, final SpexService spexService,
                           final CallerService callerService, final MediaLinkSigner signer) {
        this.catalogService = catalogService;
        this.storage = storage;
        this.spexService = spexService;
        this.callerService = callerService;
        this.signer = signer;
    }

    static String downloadName(final Track track) {
        return safe("%02d %s".formatted(track.getTrackNumber(), track.getTitle())) + "." + track.getFileExtension();
    }

    /**
     * The names, with a number added to repeats, since a zip cannot hold two entries with the same name.
     */
    static List<String> uniqueNames(final List<String> names) {
        final Set<String> used = new HashSet<>();

        return names.stream().map(name -> {
            String candidate = name;
            final int dot = name.lastIndexOf('.');

            for (int i = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); i++) {
                candidate = dot < 0 ? name + " (" + i + ")" : name.substring(0, dot) + " (" + i + ")" + name.substring(dot);
            }
            return candidate;
        }).toList();
    }

    private static String safe(final String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
    }

    static MediaType contentType(final Track track) {
        return switch (track.getFileExtension().toLowerCase(Locale.ROOT)) {
            case "flac" -> MediaType.parseMediaType("audio/flac");
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            case "m4a" -> MediaType.parseMediaType("audio/mp4");
            case "ogg", "opus" -> MediaType.parseMediaType("audio/ogg");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private static MediaType imageType(final String extension) {
        return switch (extension) {
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };
    }

    /**
     * The extension for a JPEG, PNG or WebP image, by its first bytes, or null for anything else.
     */
    static @org.jspecify.annotations.Nullable String imageExtension(final byte[] content) {
        if (content.length > 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (content.length > 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "png";
        }
        if (content.length > 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "webp";
        }
        return null;
    }

    @GetMapping("/api/tracks/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable final UUID id) {
        return audio(catalogService.playableTrack(id));
    }

    @GetMapping("/api/tracks/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable final UUID id) {
        return download(catalogService.playableTrack(id));
    }

    @GetMapping("/api/albums/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadAlbum(@PathVariable final UUID id) {
        return downloadAlbum(catalogService.visibleAlbumWithTracks(id), callerService.current().isEditor());
    }

    /**
     * A signed link to a track's audio, for the web's player, which then fetches it without a token.
     */
    @GetMapping("/api/tracks/{id}/audio/link")
    public MediaLinkSigner.Link audioLink(@PathVariable final UUID id) {
        return signer.sign("tracks/%s/audio".formatted(catalogService.playableTrack(id).getUuid()));
    }

    @GetMapping("/api/tracks/{id}/download/link")
    public MediaLinkSigner.Link downloadLink(@PathVariable final UUID id) {
        return signer.sign("tracks/%s/download".formatted(catalogService.playableTrack(id).getUuid()));
    }

    /**
     * A signed link to an album's zip; an editor's also holds the unlisted tracks, as the album page shows them.
     */
    @GetMapping("/api/albums/{id}/download/link")
    public MediaLinkSigner.Link downloadAlbumLink(@PathVariable final UUID id) {
        final Album album = catalogService.visibleAlbum(id);

        return signer.sign("albums/%s/download%s".formatted(album.getUuid(), callerService.current().isEditor() ? "/all" : ""));
    }

    @GetMapping("/media/{expires}/{signature}/tracks/{id}/audio")
    public ResponseEntity<Resource> signedAudio(@PathVariable final long expires, @PathVariable final String signature,
                                                @PathVariable final UUID id) {
        verify(expires, signature, "tracks/%s/audio".formatted(id));
        return audio(catalogService.linkedTrack(id));
    }

    @GetMapping("/media/{expires}/{signature}/tracks/{id}/download")
    public ResponseEntity<Resource> signedDownload(@PathVariable final long expires, @PathVariable final String signature,
                                                   @PathVariable final UUID id) {
        verify(expires, signature, "tracks/%s/download".formatted(id));
        return download(catalogService.linkedTrack(id));
    }

    @GetMapping("/media/{expires}/{signature}/albums/{id}/download")
    public ResponseEntity<StreamingResponseBody> signedDownloadAlbum(@PathVariable final long expires,
                                                                     @PathVariable final String signature,
                                                                     @PathVariable final UUID id) {
        verify(expires, signature, "albums/%s/download".formatted(id));
        return downloadAlbum(catalogService.linkedAlbumWithTracks(id), false);
    }

    @GetMapping("/media/{expires}/{signature}/albums/{id}/download/all")
    public ResponseEntity<StreamingResponseBody> signedDownloadAlbumAll(@PathVariable final long expires,
                                                                        @PathVariable final String signature,
                                                                        @PathVariable final UUID id) {
        verify(expires, signature, "albums/%s/download/all".formatted(id));
        return downloadAlbum(catalogService.linkedAlbumWithTracks(id), true);
    }

    private void verify(final long expires, final String signature, final String scope) {
        switch (signer.verify(expires, signature, scope)) {
            case INVALID -> throw new AccessDeniedException("Invalid media link");
            case EXPIRED -> throw new LinkExpiredException();
            case VALID -> {
            }
        }
    }

    private ResponseEntity<Resource> audio(final Track track) {
        final Path file = existingFile(track);

        return ResponseEntity.ok()
                .contentType(contentType(track))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new FileSystemResource(file));
    }

    private ResponseEntity<Resource> download(final Track track) {
        final Path file = existingFile(track);

        return ResponseEntity.ok()
                .contentType(contentType(track))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(downloadName(track), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    private Path existingFile(final Track track) {
        final Path file = storage.trackFile(track);

        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("error.track.fileMissing", track.getUuid());
        }
        return file;
    }

    private ResponseEntity<StreamingResponseBody> downloadAlbum(final Album album, final boolean editor) {
        final List<Track> tracks = album.getTracks().stream()
                .filter(track -> editor ? !track.isTrashed() : track.isListed())
                .filter(storage::exists)
                .toList();
        final List<Path> files = tracks.stream().map(storage::trackFile).toList();
        final List<String> names = uniqueNames(tracks.stream().map(MediaController::downloadName).toList());
        final String zipName = safe(album.getTitle() + (album.getYear() == null ? "" : " (" + album.getYear() + ")")) + ".zip";

        final StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.setLevel(0);
                for (int i = 0; i < files.size(); i++) {
                    zip.putNextEntry(new ZipEntry(names.get(i)));
                    Files.copy(files.get(i), zip);
                    zip.closeEntry();
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(zipName, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    /**
     * The album's own cover, else the main spex's poster from Spexregister, else 404.
     */
    @GetMapping("/api/albums/{id}/cover")
    public ResponseEntity<?> cover(@PathVariable final UUID id) {
        final Album album = catalogService.visibleAlbum(id);

        if (album.getCoverExtension() != null) {
            final Path file = storage.coverFile(album);

            if (Files.isRegularFile(file)) {
                return ResponseEntity.ok()
                        .contentType(imageType(album.getCoverExtension()))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .body(new FileSystemResource(file));
            }
        }
        if (album.mainSpex() != null) {
            return spexService.poster(album.mainSpex().getId())
                    .<ResponseEntity<?>>map(poster -> ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(poster.contentType()))
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                            .body(poster.content()))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/api/albums/{id}/cover")
    public AlbumDto uploadCover(@PathVariable final UUID id, @RequestParam("file") final MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_COVER) {
            throw new BadRequestException("error.cover.invalid");
        }

        final byte[] content;

        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        }

        final String extension = imageExtension(content);

        if (extension == null) {
            throw new BadRequestException("error.cover.invalid");
        }
        return catalogService.albumCover(id, content, extension);
    }
}
