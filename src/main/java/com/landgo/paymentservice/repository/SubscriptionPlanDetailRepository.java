package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.SubscriptionPlanDetail;
import com.landgo.paymentservice.enums.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionPlanDetailRepository extends JpaRepository<SubscriptionPlanDetail, java.util.UUID> {
    Optional<SubscriptionPlanDetail> findByPlanTypeAndIsActiveTrue(SubscriptionPlan planType);
}
