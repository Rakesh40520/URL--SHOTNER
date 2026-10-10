package com.example.urlshortener.repository;

import com.example.urlshortener.entity.OtpPurpose;
import com.example.urlshortener.entity.OtpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {

    Optional<OtpToken> findTopByEmailAndPurposeAndUsedFalseOrderByCreatedAtDesc(String email, OtpPurpose purpose);

    @Modifying
    @Query("DELETE FROM OtpToken t WHERE t.expiresAt < :now OR t.used = true")
    void deleteExpiredOrUsed(LocalDateTime now);
}
