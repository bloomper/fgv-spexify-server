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

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The audio formats Spexify accepts, by codec. WAV ({@code pcm_*}) is accepted but converted to FLAC.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum AudioFormat {
    FLAC("flac", "flac"),
    MP3("mp3", "mp3"),
    AAC("m4a", "mp4"),
    ALAC("m4a", "mp4"),
    VORBIS("ogg", "ogg"),
    OPUS("opus", "opus");

    private final String extension;
    private final String muxer;

    AudioFormat(final String extension, final String muxer) {
        this.extension = extension;
        this.muxer = muxer;
    }

    public String extension() {
        return extension;
    }

    /**
     * ffmpeg's name for the container, for {@code -f}.
     */
    public String muxer() {
        return muxer;
    }

    /**
     * The format for a codec, or null when it is not accepted. PCM is not a format: it is converted to FLAC first.
     */
    public static @Nullable AudioFormat ofCodec(final String codec) {
        return switch (codec.toLowerCase(Locale.ROOT)) {
            case "flac" -> FLAC;
            case "mp3" -> MP3;
            case "aac" -> AAC;
            case "alac" -> ALAC;
            case "vorbis" -> VORBIS;
            case "opus" -> OPUS;
            default -> null;
        };
    }

    public static boolean isPcm(final String codec) {
        return codec.toLowerCase(Locale.ROOT).startsWith("pcm_");
    }

    public static AudioFormat ofExtension(final String extension, final String format) {
        for (final AudioFormat candidate : values()) {
            if (candidate.name().equals(format)) {
                return candidate;
            }
        }
        for (final AudioFormat candidate : values()) {
            if (candidate.extension.equals(extension)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown audio format " + format + "/" + extension);
    }
}
