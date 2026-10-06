package com.example.urlshortener.repository;

import com.example.urlshortener.entity.UrlClickEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UrlClickEventRepository extends JpaRepository<UrlClickEvent, Long> {

    Page<UrlClickEvent> findByShortCodeOrderByClickedAtDesc(String shortCode, Pageable pageable);

    // Recent clicks that still have no location, oldest-attempt-first-ish: never tried, or
    // last tried before :before. Used by the retry job (ClickGeoRetryJob).
    @Query("select c from UrlClickEvent c where c.country is null and c.ipAddress is not null "
            + "and c.clickedAt > :since and (c.geoCheckedAt is null or c.geoCheckedAt < :before) "
            + "order by c.clickedAt desc")
    List<UrlClickEvent> findUnlocated(@Param("since") LocalDateTime since,
                                      @Param("before") LocalDateTime before, Pageable pageable);

    @Modifying
    @Query("update UrlClickEvent c set c.geoCheckedAt = :now where c.id = :id")
    int markGeoChecked(@Param("id") Long id, @Param("now") LocalDateTime now);

    long countByShortCode(String shortCode);

    long countByShortCodeAndCountryIsNotNull(String shortCode);

    @Modifying
    @Query("update UrlClickEvent c set c.country = :country, c.countryCode = :cc, c.region = :region, c.city = :city where c.id = :id")
    int updateGeo(@Param("id") Long id, @Param("country") String country, @Param("cc") String countryCode,
                  @Param("region") String region, @Param("city") String city);

    // Breakdowns are grouped in the database, never by loading a link's click history.
    // Each row is [label, count]; unknown values are folded into one labelled bucket.
    @Query("select coalesce(c.country, 'Unknown'), count(c) from UrlClickEvent c where c.shortCode = :code "
            + "group by coalesce(c.country, 'Unknown') order by count(c) desc")
    List<Object[]> topCountries(@Param("code") String shortCode, Pageable pageable);

    @Query("select c.city, count(c) from UrlClickEvent c where c.shortCode = :code and c.city is not null "
            + "group by c.city order by count(c) desc")
    List<Object[]> topCities(@Param("code") String shortCode, Pageable pageable);

    @Query("select coalesce(c.referrerHost, 'Direct / unknown'), count(c) from UrlClickEvent c where c.shortCode = :code "
            + "group by coalesce(c.referrerHost, 'Direct / unknown') order by count(c) desc")
    List<Object[]> topSources(@Param("code") String shortCode, Pageable pageable);

    @Query("select c.referralTag, count(c) from UrlClickEvent c where c.shortCode = :code and c.referralTag is not null "
            + "group by c.referralTag order by count(c) desc")
    List<Object[]> topReferralTags(@Param("code") String shortCode, Pageable pageable);
}
