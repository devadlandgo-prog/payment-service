package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.BillingProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingProfileRepository extends JpaRepository<BillingProfile, UUID> {
    Optional<BillingProfile> findByUserId(UUID userId);
    Optional<BillingProfile> findByStripeCustomerId(String stripeCustomerId);
}
