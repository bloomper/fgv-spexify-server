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

import nu.fgv.spexify.server.apppassword.AppPasswordService.ApiKeyCreatedDto;
import nu.fgv.spexify.server.apppassword.AppPasswordService.ApiKeyDto;
import nu.fgv.spexify.server.apppassword.AppPasswordService.AppPasswordCreatedDto;
import nu.fgv.spexify.server.apppassword.AppPasswordService.AppPasswordDto;
import nu.fgv.spexify.server.apppassword.AppPasswordService.UserSummaryDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL for app passwords, API keys and the admin's view of users. A thin adapter over {@link AppPasswordService}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AppPasswordGraphqlApi {

    private final AppPasswordService service;

    public AppPasswordGraphqlApi(final AppPasswordService service) {
        this.service = service;
    }

    @QueryMapping
    public List<AppPasswordDto> appPasswords() {
        return service.list();
    }

    @QueryMapping
    public List<ApiKeyDto> apiKeys() {
        return service.apiKeys();
    }

    @QueryMapping
    public List<UserSummaryDto> users() {
        return service.users();
    }

    @MutationMapping
    public AppPasswordCreatedDto appPasswordCreate(@Argument final String name) {
        return service.create(name);
    }

    @MutationMapping
    public @Nullable Void appPasswordRevoke(@Argument final Long id) {
        service.revoke(id);
        return null;
    }

    @MutationMapping
    public ApiKeyCreatedDto apiKeyCreate(@Argument final String name) {
        return service.apiKeyCreate(name);
    }

    @MutationMapping
    public @Nullable Void apiKeyRevoke(@Argument final Long id) {
        service.apiKeyRevoke(id);
        return null;
    }

    @MutationMapping
    public @Nullable Void userCredentialsRevoke(@Argument final Long userId) {
        service.revokeAll(userId);
        return null;
    }

    @MutationMapping
    public @Nullable Void userUnlock(@Argument final Long userId) {
        service.unlock(userId);
        return null;
    }
}
