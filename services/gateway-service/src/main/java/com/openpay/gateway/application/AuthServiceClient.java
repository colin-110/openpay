package com.openpay.gateway.application;

public interface AuthServiceClient {

    ApiKeyValidationResult validateApiKey(String apiKey);
}
