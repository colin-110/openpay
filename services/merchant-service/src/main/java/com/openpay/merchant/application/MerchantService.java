package com.openpay.merchant.application;

import com.openpay.merchant.api.CreateMerchantRequest;
import com.openpay.merchant.api.MerchantResponse;
import com.openpay.merchant.api.WebhookConfigResponse;
import com.openpay.merchant.api.PagedResponse;
import com.openpay.merchant.domain.Merchant;
import com.openpay.merchant.domain.MerchantRepository;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final int WEBHOOK_SECRET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    private final MerchantRepository merchantRepository;

    public MerchantService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Transactional
    public MerchantResponse createMerchant(CreateMerchantRequest request) {
        merchantRepository.findByMerchantCode(request.merchantCode())
                .ifPresent(existing -> {
                    throw new MerchantAlreadyExistsException(
                            "Merchant code already exists: " + request.merchantCode());
                });

        Merchant merchant = new Merchant();
        merchant.setId(UUID.randomUUID());
        merchant.setMerchantCode(request.merchantCode());
        merchant.setLegalName(request.legalName());
        merchant.setStatus(ACTIVE_STATUS);
        merchant.setWebhookUrl(request.webhookUrl());
        merchant.setDefaultCurrency(request.defaultCurrency());
        // Issued up front so a merchant that gave us a URL can receive webhooks immediately.
        if (request.webhookUrl() != null && !request.webhookUrl().isBlank()) {
            merchant.setWebhookSecret(generateSecret());
        }

        return toResponse(merchantRepository.save(merchant));
    }

    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));
        return toResponse(merchant);
    }

    @Transactional(readOnly = true)
    public PagedResponse<MerchantResponse> listMerchants(Pageable pageable) {
        Page<MerchantResponse> page = merchantRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toResponse);
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Reads the delivery configuration, including the live signing secret.
     *
     * <p>Only reachable from an admin-gated internal endpoint. Exposing a signing key on the
     * merchant-facing API would let anyone who could read a merchant forge webhooks to them.
     */
    @Transactional(readOnly = true)
    public WebhookConfigResponse getWebhookConfig(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));
        return new WebhookConfigResponse(
                merchant.getId(), merchant.getWebhookUrl(), merchant.getWebhookSecret());
    }

    /**
     * Issues or rotates the signing secret, returning it once.
     *
     * <p>Rotation is a real operation, not a convenience: a leaked secret lets anyone forge
     * notifications to the merchant, so replacing it has to be possible without re-onboarding.
     */
    @Transactional
    public WebhookConfigResponse rotateWebhookSecret(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException("Merchant not found: " + merchantId));
        merchant.setWebhookSecret(generateSecret());
        merchantRepository.save(merchant);
        return new WebhookConfigResponse(
                merchant.getId(), merchant.getWebhookUrl(), merchant.getWebhookSecret());
    }

    private String generateSecret() {
        byte[] bytes = new byte[WEBHOOK_SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private MerchantResponse toResponse(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId(),
                merchant.getMerchantCode(),
                merchant.getLegalName(),
                merchant.getStatus(),
                merchant.getWebhookUrl(),
                merchant.getDefaultCurrency(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }
}
