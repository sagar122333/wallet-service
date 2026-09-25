package com.slice.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.web.RequestIdFilter;
import com.slice.wallet.web.error.ApiErrorResponse;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * The filter chain, and the coarse route rules.
 *
 * <p><b>Where authentication actually happens:</b> {@link BearerAuthenticationFilter}, inserted
 * before Spring Security's own authentication filter. It calls user-management (through a cache)
 * and populates the security context. Everything below simply arranges for that to run and
 * decides which paths may skip it.
 *
 * <p><b>The route rules are deliberately coarse.</b> They can say "this endpoint needs a
 * caller" and "this one is open". They cannot say "may this caller touch <i>this</i> wallet?",
 * because the answer is a row in the database - that check lives in the service layer, which is
 * also what stops a future Kafka consumer from routing around it.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   BearerAuthenticationFilter bearerFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                // No cookies and no session, so there is no CSRF vector: the browser never
                // attaches the credential automatically. Disabling CSRF on a cookie-based API
                // would be a serious mistake; disabling it here is correct.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                // BEFORE the username/password filter: our filter is what establishes the
                // authentication that the authorization rules below then evaluate. Registered
                // after it, the request would be rejected as anonymous before we ever looked at
                // the token.
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthenticated(objectMapper))
                        .accessDeniedHandler(forbidden(objectMapper)));
        return http.build();
    }

    /**
     * 401 in this API's error envelope.
     *
     * <p>Mostly unreachable, because {@link BearerAuthenticationFilter} writes its own 401 and
     * stops the chain. It covers the case where a request somehow arrives at an authenticated
     * route with no principal - and without it Spring Security would answer with an empty body
     * and a {@code WWW-Authenticate} header, the only response in the whole API a client could
     * not parse with its normal error handler.
     */
    private AuthenticationEntryPoint unauthenticated(ObjectMapper objectMapper) {
        return (request, response, authException) -> write(objectMapper, response,
                HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHENTICATED,
                "a valid bearer token is required");
    }

    /** 403: we know who you are, and a retry with the same token will never help. */
    private AccessDeniedHandler forbidden(ObjectMapper objectMapper) {
        return (request, response, deniedException) -> write(objectMapper, response,
                HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
                "this token is not allowed to perform that operation");
    }

    private void write(ObjectMapper objectMapper, HttpServletResponse response, int status,
                       ErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(code, message, RequestIdFilter.currentRequestId()));
    }
}
