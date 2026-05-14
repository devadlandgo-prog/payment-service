package com.landgo.paymentservice.service;

import com.landgo.paymentservice.dto.response.BillingProfileResponse;
import com.landgo.paymentservice.entity.BillingProfile;
import com.landgo.paymentservice.exception.ResourceNotFoundException;
import com.landgo.paymentservice.repository.BillingProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingProfileService {

    private final BillingProfileRepository billingProfileRepository;

    @Transactional(readOnly = true)
    public List<BillingProfileResponse> getAllProfiles() {
        return billingProfileRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BillingProfileResponse getProfileByUserId(UUID userId) {
        return billingProfileRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Billing profile not found for user: " + userId));
    }

    @Transactional
    public void deleteProfile(UUID id) {
        BillingProfile profile = billingProfileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Billing profile not found: " + id));
        billingProfileRepository.delete(profile);
    }

    private BillingProfileResponse toResponse(BillingProfile profile) {
        return BillingProfileResponse.builder()
                .id(profile.getId())
                .userId(profile.getUserId())
                .stripeCustomerId(profile.getStripeCustomerId())
                .build();
    }
}
