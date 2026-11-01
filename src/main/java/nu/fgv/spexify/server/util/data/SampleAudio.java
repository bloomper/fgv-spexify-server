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

package nu.fgv.spexify.server.util.data;

import nu.fgv.spexify.server.media.Ffmpeg;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates audio with ffmpeg, never real recordings: tones and noise in every format the upload accepts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class SampleAudio {

    private SampleAudio() {
    }

    /**
     * One clip per accepted format, a WAV that is converted to FLAC, and a longer lossless file.
     *
     * @param seconds the length of the short clips
     */
    static List<Clip> generate(final Ffmpeg ffmpeg, final Path folder, final int seconds) throws IOException {
        Files.createDirectories(folder);

        final List<Clip> clips = new ArrayList<>();
        final String tone = "sine=frequency=%d:sample_rate=44100:duration=%d";

        clips.add(clip(ffmpeg, folder, tone.formatted(440, seconds), List.of("-c:a", "flac"), "flac", "01 Ouvertyr.flac"));
        clips.add(clip(ffmpeg, folder, tone.formatted(523, seconds), List.of("-c:a", "libmp3lame", "-b:a", "192k"), List.of("-c:a", "mp3", "-b:a", "192k"), "mp3", "02 Kärleksduett.mp3"));
        clips.add(clip(ffmpeg, folder, tone.formatted(587, seconds), List.of("-c:a", "aac", "-b:a", "160k"), "m4a", "03 Hovets klagan.m4a"));
        clips.add(clip(ffmpeg, folder, tone.formatted(659, seconds), List.of("-c:a", "libvorbis", "-q:a", "4"), List.of("-c:a", "vorbis", "-strict", "-2", "-ac", "2"), "ogg", "04 Rövarvisan.ogg"));
        clips.add(clip(ffmpeg, folder, tone.formatted(698, seconds), List.of("-c:a", "libopus", "-b:a", "96k"), List.of("-c:a", "opus", "-strict", "-2", "-ar", "48000"), "opus", "05 Ödets nummer.opus"));
        clips.add(clip(ffmpeg, folder, tone.formatted(784, seconds), List.of("-c:a", "pcm_s16le"), "wav", "06 Finalen.wav"));
        clips.add(clip(ffmpeg, folder, "anoisesrc=color=pink:sample_rate=48000:duration=" + Math.max(seconds * 6, 6),
                List.of("-c:a", "flac", "-sample_fmt", "s32"), "flac", "07 Stora numret.flac"));
        return clips;
    }

    private static Clip clip(final Ffmpeg ffmpeg, final Path folder, final String source, final List<String> codec,
                             final String extension, final String uploadName) throws IOException {
        return clip(ffmpeg, folder, source, codec, null, extension, uploadName);
    }

    /**
     * Encodes with the first codec, or with the fallback when this ffmpeg lacks it (e.g. Homebrew's without libvorbis).
     */
    private static Clip clip(final Ffmpeg ffmpeg, final Path folder, final String source, final List<String> codec,
                             final @Nullable List<String> fallback, final String extension, final String uploadName) throws IOException {
        final Path file = folder.resolve("clip-" + Math.abs(source.hashCode()) + "." + extension);

        try {
            encode(ffmpeg, source, codec, file);
        } catch (final IOException e) {
            if (fallback == null) {
                throw e;
            }
            encode(ffmpeg, source, fallback, file);
        }
        return new Clip(file, uploadName);
    }

    private static void encode(final Ffmpeg ffmpeg, final String source, final List<String> codec, final Path file) throws IOException {
        final List<String> arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", source));

        arguments.addAll(codec);
        arguments.add(file.toString());
        ffmpeg.ffmpeg(arguments);
    }

    /**
     * A generated file and the name an editor would have uploaded it as.
     */
    record Clip(Path file, String uploadName) {
    }
}
