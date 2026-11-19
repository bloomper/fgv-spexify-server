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

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * A file of a track's sheet music, fetched from Spexarkivet and passed on, for users with access to the archive.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class SheetMusicController {

    private final SheetMusicService service;

    public SheetMusicController(final SheetMusicService service) {
        this.service = service;
    }

    /**
     * The file, to read in the page or, with {@code download=true}, as an attachment.
     */
    @GetMapping("/api/tracks/{trackId}/sheet-music/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable final UUID trackId, @PathVariable final UUID fileId,
                                       @RequestParam(defaultValue = "false") final boolean download) {
        final SheetMusicService.SheetFile file = service.file(trackId, fileId);
        final String name = file.description().title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip()
                + "." + file.description().extension();
        final ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(name, StandardCharsets.UTF_8).build();

        return ResponseEntity.ok()
                .contentType(contentType(file.description().contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.content());
    }

    private static MediaType contentType(final String value) {
        try {
            final MediaType type = MediaType.parseMediaType(value);

            // Never hand the browser something it would run in the API's origin
            return type.isCompatibleWith(MediaType.TEXT_HTML) || type.getSubtype().contains("svg") || type.getSubtype().contains("javascript")
                    ? MediaType.APPLICATION_OCTET_STREAM : type;
        } catch (final RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
