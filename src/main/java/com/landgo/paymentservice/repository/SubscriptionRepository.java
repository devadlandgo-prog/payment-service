package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.Subscription;
import com.landgo.paymentservice.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByUserId(UUID userId);

    @Query("SELECT s FROM Subscription s WHERE s.userId = :userId AND s.status = 'ACTIVE' ORDER BY s.createdAt DESC")
    List<Subscription> findAllActiveByUserId(@Param("userId") UUID userId);

    @Query("SELECT s FROM Subscription s WHERE s.userId = :userId AND s.status = 'ACTIVE' AND s.planCategory = :planCategory ORDER BY s.createdAt DESC")
    List<Subscription> findAllActiveByUserIdAndPlanCategory(@Param("userId") UUID userId, @Param("planCategory") String planCategory);

    @Query("SELECT s FROM Subscription s WHERE s.userId = :userId AND s.status = 'ACTIVE' AND LOWER(s.planCategory) = LOWER(:planCategory) ORDER BY s.createdAt DESC")
    List<Subscription> findAllActiveByUserIdAndPlanCategoryIgnoreCase(@Param("userId") UUID userId, @Param("planCategory") String planCategory);

    @Query("SELECT s FROM Subscription s WHERE s.userId = :userId AND LOWER(s.planCategory) = LOWER(:planCategory) ORDER BY s.createdAt DESC")
    List<Subscription> findAllByUserIdAndPlanCategoryIgnoreCase(@Param("userId") UUID userId, @Param("planCategory") String planCategory);

    default Optional<Subscription> findActiveByUserId(UUID userId) {
        return findAllActiveByUserId(userId).stream().findFirst();
    }

    default Optional<Subscription> findActiveByUserIdAndPlanCategory(UUID userId, String planCategory) {
        return findAllActiveByUserIdAndPlanCategory(userId, planCategory).stream().findFirst();
    }

    default Optional<Subscription> findActiveByUserIdAndPlanCategoryIgnoreCase(UUID userId, String planCategory) {
        return findAllActiveByUserIdAndPlanCategoryIgnoreCase(userId, planCategory).stream().findFirst();
    }

    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.endDate < :date")
    List<Subscription> findExpiredSubscriptions(@Param("date") LocalDateTime date);

    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.autoRenew = true AND s.endDate BETWEEN :start AND :end")
    List<Subscription> findSubscriptionsToRenew(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    long countByStatus(SubscriptionStatus status);
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
}
