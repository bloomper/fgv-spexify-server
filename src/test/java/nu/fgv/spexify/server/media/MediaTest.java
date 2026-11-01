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
import nu.fgv.spexify.server.catalog.Track;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTest {

    private final Ffmpeg ffmpeg = new Ffmpeg();
    private final AudioProbe probe = new AudioProbe(ffmpeg, JsonMapper.builder().build());

    private static Track track() {
        final Album album = new Album();
        final Track track = new Track();

        album.setTitle("Nobel");
        album.setYear(2026);
        album.getSpex().setCategoryName("Chalmersspexet");
        track.setAlbum(album);
        track.setTitle("Kärleksduetten");
        track.setTrackNumber(3);
        track.setPerformers("Kungen, Drottningen");
        track.setMelody("Dancing Queen (ABBA)");
        return track;
    }

    private static boolean hasFfmpeg() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
        } catch (final IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void tagsCarryTheCatalogue() {
        final Track track = track();

        assertThat(TagWriter.tags(track))
                .containsEntry("album", "Nobel")
                .containsEntry("album_artist", "Chalmersspexet")
                .containsEntry("artist", "Kungen, Drottningen")
                .containsEntry("track", "3")
                .containsEntry("date", "2026")
                .containsEntry("genre", "Spex")
                .containsEntry("comment", "Melodi: Dancing Queen (ABBA)");
    }

    @Test
    void theHashChangesWithTheTagsOnly() {
        final Track track = track();
        final String before = TagWriter.hash(track, "none");

        assertThat(TagWriter.hash(track, "none")).isEqualTo(before);
        track.setTitle("Annan titel");
        assertThat(TagWriter.hash(track, "none")).isNotEqualTo(before);
    }

    @Test
    void recognisesCoverImagesByTheirFirstBytes() {
        assertThat(MediaController.imageExtension(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("jpg");
        assertThat(MediaController.imageExtension(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0, 0})).isEqualTo("png");
        assertThat(MediaController.imageExtension("<svg></svg>".getBytes())).isNull();
    }

    @Test
    void writesTagsThatFfprobeReadsBack(@TempDir final Path folder) throws IOException {
        Assumptions.assumeTrue(hasFfmpeg(), "ffmpeg is not installed");

        final Path file = folder.resolve("track.flac");
        final Track track = track();

        ffmpeg.ffmpeg(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "flac", file.toString()));
        track.setFileExtension("flac");
        track.setFileFormat("FLAC");
        new TagWriter(ffmpeg).write(track, file);

        final AudioProbe.Result result = probe.probe(file).orElseThrow();

        assertThat(result.codec()).isEqualTo("flac");
        assertThat(result.durationMs()).isBetween(900L, 1100L);
        assertThat(result.tag("title")).isEqualTo("Kärleksduetten");
        assertThat(result.tag("musicbrainz_releasetrackid")).isEqualTo(track.getUuid().toString());
        assertThat(result.tag("musicbrainz_albumid")).isEqualTo(track.getAlbum().getUuid().toString());
    }

    @Test
    void refusesFilesThatAreNotAudio(@TempDir final Path folder) throws IOException {
        Assumptions.assumeTrue(hasFfmpeg(), "ffmpeg is not installed");

        final Path file = Files.writeString(folder.resolve("notes.txt"), "not audio");

        assertThat(probe.probe(file)).isEmpty();
    }
}
