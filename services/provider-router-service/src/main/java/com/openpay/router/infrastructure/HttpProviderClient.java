package com.openpay.router.infrastructure;

import com.openpay.router.application.RouterProperties;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpProviderClient implements ProviderClient {

    private final RestClient restClient;

    public HttpProviderClient(RouterProperties properties) {
        // A bounded read timeout is what turns a hung acquirer into a failover instead of a
        // request that never returns.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void dispatchRefund(
            String providerName, String baseUrl, UUID refundId, UUID paymentId,
            long amount, String currency, String providerReference) {
        try {
            restClient.post()
                    .uri(baseUrl + "/provider/refunds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "refundId", refundId.toString(),
                            "paymentId", paymentId.toString(),
                            "amount", amount,
                            "currency", currency,
                            "providerReference", providerReference == null ? "" : providerReference))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new ProviderUnavailableException(
                                providerName + " refused the refund with " + clientResponse.getStatusCode(), null);
                    })
                    .toBodilessEntity();
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProviderUnavailableException(providerName + " refund call failed", exception);
        }
    }

    @Override
    public String dispatch(String providerName, String baseUrl, UUID paymentId, long amount, String currency) {
        try {
            Map<?, ?> response = restClient.post()
                    .uri(baseUrl + "/provider/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "paymentId", paymentId.toString(),
                            "amount", amount,
                            "currency", currency,
                            "merchantReference", paymentId.toString()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new ProviderUnavailableException(
                                providerName + " returned " + clientResponse.getStatusCode(), null);
                    })
                    .body(Map.class);

            Object reference = response == null ? null : response.get("providerReference");
            if (reference == null) {
                throw new ProviderUnavailableException(providerName + " returned no reference", null);
            }
            return reference.toString();
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProviderUnavailableException(providerName + " call failed", exception);
        }
    }
}
