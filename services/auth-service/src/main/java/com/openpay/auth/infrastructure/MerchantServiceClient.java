package com.openpay.auth.infrastructure;

import com.openpay.auth.application.MerchantLookupUnavailableException;
import java.util.UUID;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Confirms a merchant exists before an API key is minted for it.
 *
 * <p>Without this check the service will happily issue a working credential for a merchant id that
 * was never onboarded.
 */
public class MerchantServiceClient {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final RestClient restClient;
    private final String adminToken;

    public MerchantServiceClient(RestClient restClient, String adminToken) {
        this.restClient = restClient;
        this.adminToken = adminToken;
    }

    public boolean merchantExists(UUID merchantId) {
        try {
            restClient.get()
                    .uri("/api/v1/merchants/{merchantId}", merchantId)
                    .header(ADMIN_TOKEN_HEADER, adminToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound exception) {
            return false;
        } catch (ResourceAccessException exception) {
            throw new MerchantLookupUnavailableException("merchant-service is unreachable", exception);
        } catch (RuntimeException exception) {
            // Includes 401 from a misconfigured admin token: that is our problem, not the caller's.
            throw new MerchantLookupUnavailableException("merchant-service returned an unexpected error", exception);
        }
    }
}
