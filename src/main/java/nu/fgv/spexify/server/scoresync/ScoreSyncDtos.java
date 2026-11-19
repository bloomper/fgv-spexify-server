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


package nu.fgv.spexify.server.scoresync;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Score sync maps as the API shows them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ScoreSyncDtos {

    private ScoreSyncDtos() {
    }

    /**
     * @param stale whether the score or the recording was replaced since the map was made
     */
    public record ScoreSyncDto(UUID trackId, UUID fileId, ScoreSync.Method method, ScoreSync.Status status,
                               List<SyncPointDto> points, List<SyncJumpDto> jumps, @Nullable Double quality,
                               @Nullable String error, boolean stale, @Nullable Instant updatedAt,
                               @Nullable Long version) {
    }

    /**
     * @param seconds into the recording
     * @param measure the measure's index in written order, from 0
     * @param beat    quarter notes from the measure's start
     */
    public record SyncPointDto(double seconds, int measure, double beat) {
    }

    /**
     * After measure {@code after} is played (its {@code nth} play, from 0, or the first unused jump when null), the
     * recording goes on at measure {@code to}.
     */
    public record SyncJumpDto(int after, int to, @Nullable Integer nth) {
    }

    public record ScoreSyncInput(List<SyncPointDto> points, @Nullable List<SyncJumpDto> jumps) {
    }
}
