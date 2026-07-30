package com.openpay.security;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Validates API keys by calling auth-service.
 *
 * <p>Failure mapping matters here: a 401 from auth-service means the key is bad, but a timeout or a
 * 5xx means <em>we</em> are broken. Collapsing both into "invalid key" hides outages behind a
 * client-error status code.
 */
public class HttpAuthServiceClient implements AuthServiceClient {

    private final RestClient restClient;

    public HttpAuthServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ApiKeyPrincipal validateApiKey(String apiKey) {
        ValidateApiKeyResponse response;
        try {
            response = restClient.post()
                    .uri("/api/v1/auth/validate-key")
                    .body(new ValidateApiKeyRequest(apiKey))
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(),
                            (request, clientResponse) -> {
                                throw new InvalidApiKeyException("API key is invalid");
                            })
                    .onStatus(status -> status.value() == HttpStatus.TOO_MANY_REQUESTS.value(),
                            (request, clientResponse) -> {
                                throw new InvalidApiKeyException("Too many failed authentication attempts");
                            })
                    .body(ValidateApiKeyResponse.class);
        } catch (InvalidApiKeyException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new AuthServiceUnavailableException("Auth service is unreachable", exception);
        } catch (RuntimeException exception) {
            throw new AuthServiceUnavailableException("Auth service returned an unexpected error", exception);
        }

        if (response == null || !response.valid() || response.merchantId() == null) {
            throw new InvalidApiKeyException("API key is invalid");
        }
        return new ApiKeyPrincipal(response.merchantId(), response.scope());
    }

    record ValidateApiKeyRequest(String apiKey) {
    }

    record ValidateApiKeyResponse(boolean valid, UUID merchantId, String scope, String status) {
    }
}
