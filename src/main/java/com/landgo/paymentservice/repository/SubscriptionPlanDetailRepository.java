package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.SubscriptionPlanDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionPlanDetailRepository extends JpaRepository<SubscriptionPlanDetail, java.util.UUID> {
    Optional<SubscriptionPlanDetail> findByPlanTypeAndIsActiveTrue(String planType);
    Optional<SubscriptionPlanDetail> findByPlanType(String planType);
}
