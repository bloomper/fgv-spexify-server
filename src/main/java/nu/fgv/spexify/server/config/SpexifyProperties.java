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

package nu.fgv.spexify.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Spexify's settings under {@code spexify.*}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties(prefix = "spexify")
public record SpexifyProperties(String timeZone,
                                Keycloak keycloak,
                                Security security,
                                Media media,
                                Navidrome navidrome,
                                Crypto crypto,
                                Spexregister spexregister,
                                Spexflix spexflix,
                                Data data) {

    public record Keycloak(String url, String realm, String serviceClientId, String serviceClientSecret,
                           String adminClientId, String adminClientSecret) {
    }

    public record Security(List<String> authorizedParties, Duration accessCache) {
    }

    public record Media(Path root, DataSize maxTrackSize) {
    }

    public record Navidrome(String url, String systemUser) {
    }

    public record Crypto(String appPasswordKey) {
    }

    public record Spexregister(String graphqlUrl) {
    }

    public record Spexflix(String graphqlUrl) {
    }

    public record Data(boolean importSampleData, String sampleDataSize, String sampleUserPassword,
                       String sampleAppPassword) {
    }
}
