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

import nu.fgv.spexify.server.util.security.KeycloakJwtRolesConverter;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.DelegatingJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * Three stateless chains. MCP ({@code /api/mcp}) is a JWT resource server bound to {@code fgv-spexify-mcp} tokens with
 * the MCP URL as audience; those tokens are refused everywhere else. The Subsonic API ({@code /rest/**}) is open at
 * HTTP level, because the gateway authenticates app passwords, API keys and tokens itself and answers in the Subsonic
 * format. Everything else is a JWT resource
 * server bound to {@code fgv-spexify} tokens (and Spexflix's service account); GraphQL is open at HTTP level because the
 * services decide access, and so are the signed media links under {@code /media/}, whose signature is the access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain mcpSecurityFilterChain(final HttpSecurity http,
                                                      @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}") final String mcpEndpoint,
                                                      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") final String issuerUri,
                                                      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") final String jwkSetUri,
                                                      @Value("${spexify.mcp.authorized-parties}") final List<String> authorizedParties,
                                                      final SpexifyProperties properties,
                                                      final JwtAuthenticationConverter jwtAuthenticationConverter) {
        // The API's public address, as the MCP client and Keycloak's audience mapper know it
        final String resource = UriComponentsBuilder.fromUriString(properties.media().publicUrl()).replacePath(mcpEndpoint).build().toUriString();
        final JwtDecoder jwtDecoder = new SupplierJwtDecoder(() -> {
            // Like the rest of the API: the keys from Keycloak's internal address when one is set, as in docker compose
            final NimbusJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                    ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                    : NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new JwtAudienceValidator(resource),
                    new JwtClaimValidator<String>("azp", authorizedParties::contains),
                    accessTokenTypeValidator(),
                    new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText),
                    new JwtClaimValidator<String>("email", StringUtils::hasText)
            ));
            return decoder;
        });

        http
                .securityMatcher(mcpEndpoint, "/.well-known/oauth-protected-resource/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(HttpMethod.GET, "/.well-known/oauth-protected-resource/**").permitAll()
                                .anyRequest().authenticated()
                )
                .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> mcp
                        .authorizationServer(issuerUri)
                        .resourcePath(mcpEndpoint)
                        .resourceName("Spexify")
                        .validateAudienceClaim(false)
                        .jwtDecoder(jwtDecoder))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain subsonicSecurityFilterChain(final HttpSecurity http) {
        http
                .securityMatcher("/rest/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(final HttpSecurity http,
                                                   final JwtAuthenticationConverter jwtAuthenticationConverter) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                                .requestMatchers(HttpMethod.GET, "/docs/**").permitAll()
                                // Signed links; MediaController checks the signature
                                .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/graphql").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/graphql").permitAll()
                                .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    public OAuth2TokenValidator<Jwt> authorizedPartyValidator(final SpexifyProperties properties) {
        return new JwtClaimValidator<String>("azp", properties.security().authorizedParties()::contains);
    }

    @Bean
    public OAuth2TokenValidator<Jwt> accessTokenTypeValidator() {
        return new JwtClaimValidator<String>("typ", "Bearer"::equals);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        final DelegatingJwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new DelegatingJwtGrantedAuthoritiesConverter(
                new JwtGrantedAuthoritiesConverter(),
                new KeycloakJwtRolesConverter()
        );
        final JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();

        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return jwtAuthenticationConverter;
    }
}
