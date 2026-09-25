package com.slice.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

/**
 * Base for the end-to-end tests.
 *
 * <p>{@code RANDOM_PORT} with a real {@code TestRestTemplate}, not {@code MockMvc} and certainly
 * not by calling controller methods directly: the security filter chain, {@code @Valid}, Jackson
 * serialisation and the exception handler are all part of this API's contract, and none of them
 * run when a controller is invoked as a plain Java object.
 *
 * <p>Tests run with {@code wallet.auth.mode=stub}, so a token is just a string that names a user
 * and its scopes - see {@link com.slice.wallet.security.StubUserManagementClient}. No identity
 * service has to be running.
 *
 * <p>Every test creates fixtures under a fresh random user id, so tests never collide and
 * nothing has to be truncated between runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class ApiTestBase {

    @Autowired
    protected TestRestTemplate rest;

    protected static String newUserId() {
        return "user-" + UUID.randomUUID();
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    /**
     * A bearer token for the stub validator: {@code userId|scope,scope}.
     *
     * <p>With no scopes listed the stub grants all of them, which is what makes the happy-path
     * tests readable; naming scopes explicitly is how the 403 cases are built.
     */
    protected String token(String userId, String... scopes) {
        return scopes.length == 0 ? userId : userId + "|" + String.join(",", scopes);
    }

    protected ResponseEntity<JsonNode> post(String path, String token, String idempotencyKey,
                                            Object body) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(token, idempotencyKey)), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(token, null)), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String token,
                                                Object body) {
        return rest.exchange(path, method,
                new HttpEntity<>(body, headers(token, newKey())), JsonNode.class);
    }

    protected HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    // ------------------------------------------------------------------ fixtures

    protected String createWallet(String token, String currency) {
        ResponseEntity<JsonNode> response =
                post("/v1/wallets", token, newKey(), java.util.Map.of("currency", currency));
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("wallet creation failed: " + response.getBody());
        }
        return response.getBody().get("walletId").asText();
    }

    protected void credit(String walletId, String token, long amountMinor) {
        ResponseEntity<JsonNode> response = post("/v1/wallets/" + walletId + "/credits", token,
                newKey(), java.util.Map.of("amountMinor", amountMinor, "currency", "INR"));
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("credit failed: " + response.getBody());
        }
    }

    protected long balanceMinor(String walletId, String token) {
        return get("/v1/wallets/" + walletId, token).getBody().get("balanceMinor").asLong();
    }

    protected String errorCode(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        return body == null || body.get("error") == null
                ? null
                : body.get("error").get("code").asText();
    }
}
