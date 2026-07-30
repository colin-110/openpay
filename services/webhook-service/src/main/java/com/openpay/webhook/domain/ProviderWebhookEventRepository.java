package com.openpay.webhook.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProviderWebhookEventRepository extends JpaRepository<ProviderWebhookEvent, UUID> {

    Optional<ProviderWebhookEvent> findByProviderNameAndProviderEventId(
            String providerName, String providerEventId);
}
