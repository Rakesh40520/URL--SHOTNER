package com.example.urlshortener.repository;

import com.example.urlshortener.entity.UrlClickEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UrlClickEventRepository extends JpaRepository<UrlClickEvent, Long> {

    Page<UrlClickEvent> findByShortCodeOrderByClickedAtDesc(String shortCode, Pageable pageable);
}
