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
        final nu.fgv.spexify.server.catalog.SpexRef spex = new nu.fgv.spexify.server.catalog.SpexRef();

        spex.setCategoryName("Chalmersspexet");
        album.getSpex().add(spex);
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

    @Test
    void measuresLoudnessAndWritesReplayGainThatFfprobeReadsBack(@TempDir final Path folder) throws IOException {
        Assumptions.assumeTrue(hasFfmpeg(), "ffmpeg is not installed");

        final Path file = folder.resolve("track.flac");
        final Track track = track();

        ffmpeg.ffmpeg(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "flac", file.toString()));

        final Loudness loudness = new LoudnessMeter(ffmpeg).measure(file).orElseThrow();

        // ffmpeg's sine is at 1/8 of full scale: about -21 LUFS, peaking at about -18 dBFS
        assertThat(loudness.integratedLufs()).isBetween(-23.0, -20.0);
        assertThat(loudness.truePeakDbfs()).isBetween(-19.0, -17.0);

        track.setFileExtension("flac");
        track.setFileFormat("FLAC");
        track.setDurationMs(3000);
        track.setLoudness(loudness);
        track.getAlbum().setPublished(true);
        track.getAlbum().getTracks().add(track);
        new TagWriter(ffmpeg).write(track, file);

        final AudioProbe.Result result = probe.probe(file).orElseThrow();

        assertThat(result.tag("replaygain_track_gain")).matches("[0-9]+\\.[0-9]{2} dB");
        assertThat(result.tag("replaygain_album_gain")).isEqualTo(result.tag("replaygain_track_gain"));
        assertThat(result.tag("replaygain_track_peak")).startsWith("0.1");
    }

    @Test
    void readsTheSummaryOfEbur128() {
        final String log = """
                [Parsed_ebur128_0 @ 0x1] Summary:

                  Integrated loudness:
                    I:         -14.3 LUFS
                    Threshold: -24.3 LUFS

                  True peak:
                    Peak:       -0.4 dBFS
                """;

        assertThat(LoudnessMeter.parse(log)).contains(new Loudness(-14.3, -0.4));
        assertThat(LoudnessMeter.parse("    I:         -inf LUFS\n    Peak:      -inf dBFS")).isEmpty();
        assertThat(new Loudness(-14.3, -0.4).gainDb()).isCloseTo(-3.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void anAlbumsLoudnessAveragesItsTracksEnergyOverTime() {
        final Loudness album = Loudness.album(List.of(
                new Loudness.Measured(new Loudness(-10.0, -1.0), 60_000),
                new Loudness.Measured(new Loudness(-20.0, -3.0), 60_000)));

        // Mostly the loud track: 10*log10((10^-1 + 10^-2) / 2)
        assertThat(album).isNotNull();
        assertThat(album.integratedLufs()).isCloseTo(-12.6, org.assertj.core.data.Offset.offset(0.05));
        assertThat(album.truePeakDbfs()).isEqualTo(-1.0);
        assertThat(Loudness.album(List.of())).isNull();
    }
}
