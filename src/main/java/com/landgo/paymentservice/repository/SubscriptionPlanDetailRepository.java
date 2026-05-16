package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.SubscriptionPlanDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPlanDetailRepository extends JpaRepository<SubscriptionPlanDetail, java.util.UUID> {
    Optional<SubscriptionPlanDetail> findByPlanTypeAndIsActiveTrue(String planType);
    Optional<SubscriptionPlanDetail> findByPlanType(String planType);
    Optional<SubscriptionPlanDetail> findByPlanTypeAndPlanCategory(String planType, String planCategory);
    Optional<SubscriptionPlanDetail> findByPlanTypeAndPlanCategoryAndIsActiveTrue(String planType, String planCategory);
    Optional<SubscriptionPlanDetail> findByIdAndIsActiveTrue(UUID id);
    List<SubscriptionPlanDetail> findAllByPlanTypeAndIsActiveTrue(String planType);
}
