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

package nu.fgv.spexify.server.apppassword;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexify.server.user.AppUser;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A password for one app on one device. Stored encrypted, because Subsonic's token authentication sends
 * {@code md5(password + salt)} and the server must compute the same.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "app_password")
@Getter
@Setter
public class AppPassword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private @Nullable Instant lastUsedAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    /**
     * When it expires unless it is used: a number of months after it was last used, or created.
     */
    public Instant expiresAt(final int months) {
        final Instant from = lastUsedAt != null ? lastUsedAt : createdAt;

        return from.atZone(java.time.ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
