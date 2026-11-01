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

package nu.fgv.spexify.server.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * The spex an album is about, by its id in Spexregister, with its title, year and category copied so that the
 * catalogue works while Spexregister is unavailable. Refreshed daily.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Embeddable
@Getter
@Setter
public class SpexRef {

    @Column(name = "spex_id", length = 36)
    private @Nullable UUID id;

    @Column(name = "spex_title")
    private @Nullable String title;

    @Column(name = "spex_year")
    private @Nullable Integer year;

    @Column(name = "spex_revival")
    private @Nullable Boolean revival;

    @Column(name = "spex_category_id", length = 36)
    private @Nullable UUID categoryId;

    @Column(name = "spex_category_name")
    private @Nullable String categoryName;

    @Column(name = "spex_refreshed_at")
    private @Nullable Instant refreshedAt;
}
