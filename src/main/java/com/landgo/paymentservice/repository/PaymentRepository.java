package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    @Query("SELECT p FROM Payment p WHERE p.userId = :userId AND p.deleted = false ORDER BY p.createdAt DESC")
    Page<Payment> findByUserId(@Param("userId") UUID userId, Pageable pageable);
}
