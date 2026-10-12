package com.example.urlshortener.repository;

import com.example.urlshortener.entity.EmailCode;
import com.example.urlshortener.entity.EmailCodePurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailCodeRepository extends JpaRepository<EmailCode, Long> {

    Optional<EmailCode> findFirstByEmailAndPurposeOrderByCreatedAtDesc(String email, EmailCodePurpose purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, EmailCodePurpose purpose, LocalDateTime after);

    @Modifying
    @Query("delete from EmailCode c where c.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);

    @Modifying
    @Query("delete from EmailCode c where c.email = :email and c.purpose = :purpose")
    int deleteAllFor(@Param("email") String email, @Param("purpose") EmailCodePurpose purpose);
}
