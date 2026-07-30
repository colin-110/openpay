package com.openpay.gateway.infrastructure;

import com.openpay.gateway.application.ApiKeyValidationResult;
import com.openpay.gateway.application.AuthServiceClient;
import com.openpay.gateway.application.InvalidApiKeyException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AuthServiceHttpClient implements AuthServiceClient {

    private final RestClient restClient;

    public AuthServiceHttpClient(RestClient authRestClient) {
        this.restClient = authRestClient;
    }

    @Override
    public ApiKeyValidationResult validateApiKey(String apiKey) {
        ValidateApiKeyResponse response = restClient.post()
                .uri("/api/v1/auth/validate-key")
                .body(new ValidateApiKeyRequest(apiKey))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    throw new InvalidApiKeyException("API key validation failed");
                })
                .body(ValidateApiKeyResponse.class);

        if (response == null || !response.valid()) {
            throw new InvalidApiKeyException("API key validation failed");
        }
        return new ApiKeyValidationResult(
                response.valid(),
                response.merchantId(),
                response.scope(),
                response.status());
    }
}
