package com.landgo.paymentservice.repository;

import com.landgo.paymentservice.entity.ListingCreditLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ListingCreditLedgerRepository extends JpaRepository<ListingCreditLedgerEntry, UUID> {

    Optional<ListingCreditLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Page<ListingCreditLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
