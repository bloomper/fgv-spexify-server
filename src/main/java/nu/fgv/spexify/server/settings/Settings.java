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

package nu.fgv.spexify.server.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * The one row of settings.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
public class Settings {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    @Column(name = "welcome_sv")
    private @Nullable String welcomeSv;

    @Column(name = "welcome_en")
    private @Nullable String welcomeEn;

    @Column(name = "app_password_expiry_months", nullable = false)
    private int appPasswordExpiryMonths = 12;

    @Column(name = "lockout_attempts", nullable = false)
    private int lockoutAttempts = 10;

    @Column(name = "lockout_minutes", nullable = false)
    private int lockoutMinutes = 15;

    @Version
    private Long version;

    @Column(name = "last_modified_by")
    private @Nullable String lastModifiedBy;

    @Column(name = "last_modified_at")
    private @Nullable Instant lastModifiedAt;
}
