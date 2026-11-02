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

import java.util.Collection;

/**
 * A track's loudness as EBU R 128 measures it, and the ReplayGain values that follow from it. ReplayGain 2.0 aims at
 * -18 LUFS; a positive gain means the track is quieter than that.
 *
 * @param integratedLufs the integrated loudness, in LUFS
 * @param truePeakDbfs   the true peak, in dBFS
 * @author Anders Jacobsson
 * @since 1.0
 */
public record Loudness(double integratedLufs, double truePeakDbfs) {

    public static final double REFERENCE_LUFS = -18.0;

    /**
     * The loudness of tracks played one after another: their energy averaged over their durations, and their highest
     * peak. Null without any measured track.
     */
    public static @Nullable Loudness album(final Collection<Measured> tracks) {
        double energy = 0;
        double duration = 0;
        double peak = Double.NEGATIVE_INFINITY;

        for (final Measured track : tracks) {
            final double seconds = Math.max(track.durationMs(), 1) / 1000.0;

            energy += seconds * Math.pow(10, track.loudness().integratedLufs() / 10);
            duration += seconds;
            peak = Math.max(peak, track.loudness().truePeakDbfs());
        }
        return duration == 0 ? null : new Loudness(10 * Math.log10(energy / duration), peak);
    }

    /**
     * The ReplayGain gain, in dB.
     */
    public double gainDb() {
        return REFERENCE_LUFS - integratedLufs;
    }

    /**
     * The ReplayGain peak, as a linear amplitude where 1.0 is full scale.
     */
    public double peakAmplitude() {
        return Math.pow(10, truePeakDbfs / 20);
    }

    /**
     * A measured track, for {@link #album(Collection)}.
     */
    public record Measured(Loudness loudness, long durationMs) {
    }
}
