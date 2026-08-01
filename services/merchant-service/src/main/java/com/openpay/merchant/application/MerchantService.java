package com.openpay.merchant.application;

import com.openpay.merchant.api.CreateMerchantRequest;
import com.openpay.merchant.api.MerchantResponse;
import com.openpay.merchant.api.WebhookConfigResponse;
import com.openpay.merchant.api.PagedResponse;
import com.openpay.merchant.domain.Merchant;
import org.springframework.beans.factory.annotation.Value;
import com.openpay.audit.AuditAction;
import com.openpay.audit.AuditRecorder;
import com.openpay.security.OutboundUrlPolicy;
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
    private final AuditRecorder auditRecorder;

    /**
     * Local development points webhooks at localhost, which is exactly what the policy exists to
     * refuse. Opt in explicitly rather than making the safe rule conditional on guesswork.
     */
    private final boolean allowLoopbackWebhooks;

    public MerchantService(
            MerchantRepository merchantRepository,
            AuditRecorder auditRecorder,
            @Value("${openpay.merchant.allow-loopback-webhooks:false}") boolean allowLoopbackWebhooks) {
        this.allowLoopbackWebhooks = allowLoopbackWebhooks;
        this.merchantRepository = merchantRepository;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public MerchantResponse createMerchant(CreateMerchantRequest request) {
        merchantRepository.findByMerchantCode(request.merchantCode())
                .ifPresent(existing -> {
                    throw new MerchantAlreadyExistsException(
                            "Merchant code already exists: " + request.merchantCode());
                });

        // Checked before anything is stored, so an unsendable URL is a bad request rather than a
        // delivery failure discovered hours later. The same policy is applied again at connect
        // time by notification-service, which is the check that actually protects the network.
        OutboundUrlPolicy.requireDeliverable(request.webhookUrl(), allowLoopbackWebhooks);

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

        Merchant saved = merchantRepository.save(merchant);
        auditRecorder.record(AuditAction.MERCHANT_CREATED, "admin-token", saved.getMerchantCode(),
                saved.getId(), "Default currency " + saved.getDefaultCurrency());
        return toResponse(saved);
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
        // The fact of the rotation, never the secret. An audit log holding live signing keys would
        // be the easiest place on the platform to steal one from.
        auditRecorder.record(AuditAction.WEBHOOK_SECRET_ROTATED, "admin-token",
                merchant.getMerchantCode(), merchant.getId(), "Previous secret invalidated");
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
