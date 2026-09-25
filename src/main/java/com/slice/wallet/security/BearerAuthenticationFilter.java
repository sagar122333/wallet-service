package com.slice.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.web.RequestIdFilter;
import com.slice.wallet.web.error.ApiErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authentication. Runs once per request, before anything else in the application sees it.
 *
 * <p><b>The whole flow, in order:</b>
 * <ol>
 *   <li>Pull the {@code Authorization} header. No header, or not a Bearer token → <b>401</b>,
 *       and the request never reaches a controller.</li>
 *   <li>Hand the token to {@link CachingTokenValidator}, which answers from its cache or calls
 *       user-management.</li>
 *   <li>Empty answer → the token is unknown, expired or revoked → <b>401</b>.</li>
 *   <li>{@link IdentityUnavailableException} → we could not check → <b>503</b> with
 *       {@code Retry-After}. Emphatically not a 401.</li>
 *   <li>Otherwise build a {@link Caller}, wrap it in an {@code Authentication} whose authorities
 *       are {@code SCOPE_*}, and put it in the {@link SecurityContextHolder}.</li>
 *   <li>Clear the context in a {@code finally}. Worker threads are pooled; a leftover principal
 *       would authenticate the <i>next</i> request on that thread as this user.</li>
 * </ol>
 *
 * <p>From here on, {@code @PreAuthorize} reads those authorities, and
 * {@link CallerArgumentResolver} turns the principal back into a {@link Caller} for controller
 * methods. Nothing downstream ever sees a token.
 */
@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final CachingTokenValidator tokens;
    private final ObjectMapper objectMapper;

    public BearerAuthenticationFilter(CachingTokenValidator tokens, ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    /**
     * The health probe is the one thing that must answer without a token: a load balancer has no
     * credentials, and a probe that can fail for an auth reason is a probe that lies about the
     * service being up.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, 401, ErrorCode.UNAUTHENTICATED, "a bearer token is required");
            return;
        }
        String token = header.substring(BEARER.length()).trim();

        Optional<AuthenticatedUser> user;
        try {
            user = tokens.validate(token);
        } catch (IdentityUnavailableException e) {
            // We do not know whether this token is good. Say so honestly.
            log.error("token validation failed - user-management is unreachable", e);
            response.setHeader("Retry-After", "2");
            reject(response, 503, ErrorCode.IDENTITY_UNAVAILABLE,
                    "cannot verify credentials right now; retry shortly");
            return;
        }

        if (user.isEmpty()) {
            // The same message for unknown, expired and revoked. An error body must not tell an
            // attacker which half of the guess was right.
            reject(response, 401, ErrorCode.UNAUTHENTICATED, "invalid or expired token");
            return;
        }

        Caller caller = user.get().toCaller();
        List<SimpleGrantedAuthority> authorities = caller.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();

        try {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(caller, null, authorities));
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Written here rather than thrown, because a filter runs outside the DispatcherServlet -
        // @RestControllerAdvice never sees it. Using the same envelope keeps auth failures
        // parseable by the client's ordinary error handler.
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(code, message, RequestIdFilter.currentRequestId()));
    }
}
