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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures a file's loudness with ffmpeg's {@code ebur128} filter, for ReplayGain.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class LoudnessMeter {

    private static final Pattern INTEGRATED = Pattern.compile("^\\s*I:\\s*(-?[0-9.]+|-inf) LUFS", Pattern.MULTILINE);
    private static final Pattern PEAK = Pattern.compile("^\\s*Peak:\\s*(-?[0-9.]+|-inf) dBFS", Pattern.MULTILINE);

    private final Ffmpeg ffmpeg;

    public LoudnessMeter(final Ffmpeg ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * Reads the summary ebur128 logs at the end: the last integrated loudness and true peak. Empty for silence or a log
     * without them.
     */
    static Optional<Loudness> parse(final String log) {
        final Double integrated = last(INTEGRATED.matcher(log));
        final Double peak = last(PEAK.matcher(log));

        return integrated == null || peak == null || integrated.isInfinite() ? Optional.empty()
                : Optional.of(new Loudness(integrated, peak.isInfinite() ? -100.0 : peak));
    }

    private static @Nullable Double last(final Matcher matcher) {
        Double value = null;

        while (matcher.find()) {
            value = "-inf".equals(matcher.group(1)) ? Double.NEGATIVE_INFINITY : Double.parseDouble(matcher.group(1));
        }
        return value;
    }

    public Optional<Loudness> measure(final Path file) throws IOException {
        final List<String> arguments = new ArrayList<>(Ffmpeg.AUDIO_INPUT);

        arguments.addAll(List.of("-i", file.toString(), "-map", "0:a:0", "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"));
        return parse(ffmpeg.analyse(arguments));
    }
}
