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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Sheet music as Spexify shows it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class SheetMusicDtos {

    private SheetMusicDtos() {
    }

    /**
     * What the web can do with a file: read a PDF, draw MusicXML as notes, play MIDI; others are only downloaded.
     */
    public enum SheetFileKind {
        PDF,
        MUSIC_XML,
        MIDI,
        OTHER;

        public static SheetFileKind of(final String extension, final String contentType) {
            final String ext = extension.toLowerCase(Locale.ROOT);

            if ("pdf".equals(ext) || "application/pdf".equals(contentType)) {
                return PDF;
            }
            if ("musicxml".equals(ext) || "mxl".equals(ext) || ("xml".equals(ext) && contentType.contains("xml"))
                    || contentType.startsWith("application/vnd.recordare.musicxml")) {
                return MUSIC_XML;
            }
            if ("mid".equals(ext) || "midi".equals(ext) || "audio/midi".equals(contentType) || "audio/x-midi".equals(contentType)) {
                return MIDI;
            }
            return OTHER;
        }
    }

    /**
     * The arrangement of the number a track is a recording of.
     *
     * @param archiveUrl the arrangement's page in Spexarkivet, or null when its address is not configured
     */
    public record SheetMusicDto(UUID arrangementId, @Nullable String archiveUrl, List<SheetVersionDto> versions) {

        public @Nullable SheetFileDto file(final UUID id) {
            return versions.stream().flatMap(version -> version.files().stream()).filter(file -> file.id().equals(id)).findFirst().orElse(null);
        }
    }

    public record SheetVersionDto(UUID id, String label, @Nullable String arranger, boolean recommended,
                                  @Nullable String key, @Nullable String tempo, List<SheetFileDto> files) {
    }

    public record SheetFileDto(UUID id, String title, String name, String extension, String contentType, double size,
                               String checksum, @Nullable Integer pages, String roleSv, String roleEn,
                               List<String> instrumentsSv, List<String> instrumentsEn, @Nullable String desk,
                               SheetFileKind kind) {
    }
}
