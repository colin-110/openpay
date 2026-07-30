package com.openpay.router.infrastructure;

import java.util.UUID;

public interface ProviderClient {

    /**
     * @throws ProviderUnavailableException if the provider refused, errored, or did not answer in time
     */
    String dispatch(String providerName, String baseUrl, UUID paymentId, long amount, String currency);
}
