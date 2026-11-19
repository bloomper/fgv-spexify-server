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

import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.ScoreSyncInput;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.SyncJumpDto;
import nu.fgv.spexify.server.scoresync.ScoreSyncDtos.SyncPointDto;
import nu.fgv.spexify.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreSyncServiceTest {

    @Test
    void pointsMustComeInTheOrderOfTheRecording() {
        assertThatCode(() -> ScoreSyncService.validate(new ScoreSyncInput(List.of(new SyncPointDto(0.5, 0, 0), new SyncPointDto(2, 1, 1.5)),
                List.of(new SyncJumpDto(7, 0, null))))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ScoreSyncService.validate(new ScoreSyncInput(List.of(new SyncPointDto(2, 0, 0), new SyncPointDto(2, 1, 0)), null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ScoreSyncService.validate(new ScoreSyncInput(List.of(new SyncPointDto(1, -1, 0)), null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ScoreSyncService.validate(new ScoreSyncInput(List.of(), null))).isInstanceOf(BadRequestException.class);
    }
}
