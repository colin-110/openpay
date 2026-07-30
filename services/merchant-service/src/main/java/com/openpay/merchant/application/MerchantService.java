package com.openpay.merchant.application;

import com.openpay.merchant.api.CreateMerchantRequest;
import com.openpay.merchant.api.MerchantResponse;
import com.openpay.merchant.api.PagedResponse;
import com.openpay.merchant.domain.Merchant;
import com.openpay.merchant.domain.MerchantRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private static final String ACTIVE_STATUS = "ACTIVE";

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
