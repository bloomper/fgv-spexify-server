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

package nu.fgv.spexify.server.spex;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A number (one song) of a spex in Spexipedia, which a track may be a recording of, with its number kind.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record NumberDto(UUID id, @Nullable UUID spexId, int act, int position, String title, @Nullable UUID kindId) {

    public NumberDto(final UUID id, final @Nullable UUID spexId, final int act, final int position, final String title) {
        this(id, spexId, act, position, title, null);
    }
}
