package com.slice.wallet.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The real client: one HTTP call to user-management per validation.
 *
 * <p>Active when {@code wallet.auth.mode=remote}.
 *
 * <p><b>Timeouts are not optional.</b> Without them a hung identity service does not make this
 * service slow, it makes it stop: every request thread parks waiting on a socket that will never
 * answer, and the pool is exhausted within seconds. A one-second connect and two-second read
 * budget means a bad identity service costs us a bounded amount of latency and then a clean 503.
 *
 * <p><b>Four outcomes, three answers.</b> 200 with {@code active: true} is a caller. 200 with
 * {@code active: false}, and 401/404, all mean "not a valid token" - a 401 from us. Anything else
 * - a timeout, a connection refused, a 500 - means we <i>do not know</i>, and that must become a
 * 503, never a 401. Turning an outage into "your session expired" logs every customer out at
 * once and then stampedes the login flow.
 */
@Component
@ConditionalOnProperty(name = "wallet.auth.mode", havingValue = "remote")
public class HttpUserManagementClient implements UserManagementClient {

    private static final Logger log = LoggerFactory.getLogger(HttpUserManagementClient.class);

    /** What we send. The token travels in the body, never in the URL - see below. */
    record ValidateRequest(String token) {
    }

    /** What user-management answers. Unknown fields are ignored: their API may grow. */
    record ValidateResponse(boolean active, String userId, List<String> scopes) {
    }

    private final RestClient restClient;
    private final String validatePath;

    public HttpUserManagementClient(
            @Value("${wallet.auth.user-management-url}") String baseUrl,
            @Value("${wallet.auth.validate-path:/internal/v1/tokens/validate}") String validatePath,
            @Value("${wallet.auth.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${wallet.auth.read-timeout:PT2S}") Duration readTimeout) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.validatePath = validatePath;
        log.info("token validation will call {}{}", baseUrl, validatePath);
    }

    @Override
    public Optional<AuthenticatedUser> validate(String token) {
        try {
            // POST with the token in the BODY, not GET with it in the path or a query string:
            // URLs end up in access logs, proxy logs and browser history, and a bearer token in
            // a log file is a credential in a log file.
            ValidateResponse response = restClient.post()
                    .uri(validatePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ValidateRequest(token))
                    .retrieve()
                    .body(ValidateResponse.class);

            if (response == null || !response.active() || response.userId() == null) {
                return Optional.empty();
            }
            Set<String> scopes = response.scopes() == null ? Set.of() : Set.copyOf(response.scopes());
            return Optional.of(new AuthenticatedUser(response.userId(), scopes));

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404) {
                // A definite "no". Not an outage.
                return Optional.empty();
            }
            throw new IdentityUnavailableException(
                    "user-management answered " + status + " validating a token", e);

        } catch (RestClientException e) {
            // Timeout, connection refused, DNS failure, malformed response.
            throw new IdentityUnavailableException("user-management could not be reached", e);
        }
    }
}
