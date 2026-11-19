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


package nu.fgv.spexify.server.lyrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsServiceTest {

    @Test
    void waveformPeaksAreScaledToTheLoudest() {
        final float[] samples = {0f, 0.1f, -0.5f, 0.2f, 0.05f, -0.05f, 0.25f, 0f};

        assertThat(LyricsService.peaks(samples, 4)).containsExactly(0.2, 1.0, 0.1, 0.5);
    }
}
