package com.openpay.security;

public interface AuthServiceClient {

    /**
     * @throws InvalidApiKeyException if the key is rejected by the auth service
     * @throws AuthServiceUnavailableException if the auth service could not answer
     */
    ApiKeyPrincipal validateApiKey(String apiKey);
}
