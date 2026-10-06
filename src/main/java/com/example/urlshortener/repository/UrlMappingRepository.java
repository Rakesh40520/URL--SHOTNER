package com.example.urlshortener.repository;

import com.example.urlshortener.entity.UrlMapping;
import com.example.urlshortener.entity.UrlStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    List<UrlMapping> findByExpiresAtBefore(LocalDateTime dateTime);

    // Used on the redirect cache-hit path (see
    // UrlShortenerService.resolve) so a repeat click on an already-cached
    // link never needs to SELECT the row at all - just this one UPDATE. A
    // bulk update rather than load-mutate-save also sidesteps a lost-update
    // race between two concurrent clicks on the same link: two overlapping
    // "clickCount = clickCount + 1" statements both apply correctly,
    // whereas two overlapping load-then-save cycles could have the second
    // save overwrite the first's increment.
    @Modifying(clearAutomatically = true)
    @Query("update UrlMapping m set m.clickCount = m.clickCount + 1, m.lastAccessedAt = :now where m.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode, @Param("now") LocalDateTime now);

    // Cursor-paginated (by id, not offset) so the scheduled Safe Browsing
    // re-check (recheckActiveLinksForThreats) can walk every ACTIVE link in
    // fixed-size batches without loading the whole table into memory. An
    // id cursor - rather than Pageable's normal page-number offset - is
    // required here specifically because that job flips some rows'
    // status away from ACTIVE as it goes: offset-based paging would silently
    // skip rows once earlier pages shrink the ACTIVE result set out from
    // under it, while "id > lastSeenId" is unaffected by rows elsewhere
    // changing status.
    List<UrlMapping> findByStatusAndIdGreaterThanOrderByIdAsc(UrlStatus status, Long id, Pageable pageable);

    Page<UrlMapping> findByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Aggregate counts for the dashboard summary cards, computed in the
    // database rather than by pulling every row into memory to sum/filter
    // in Java - matters once a user has more links than fit on one page.
    long countByUser_Id(Long userId);

    // Monthly quota check for subscription plans: links this user created on
    // or after the start of the current calendar month.
    long countByUser_IdAndCreatedAtGreaterThanEqual(Long userId, LocalDateTime from);

    long countByUser_IdAndExpiresAtBefore(Long userId, LocalDateTime dateTime);

    // "Blocked" for the dashboard summary card - links the owner disabled
    // or that got flagged, excluding ones that are ALSO expired (those are
    // counted under "expired" instead, matching statusLabel() in
    // UrlShortenerService, which always shows "Expired" ahead of
    // "Disabled"/"Flagged" for a link that's both).
    @Query("select count(m) from UrlMapping m where m.user.id = :userId and m.status <> :status "
            + "and (m.expiresAt is null or m.expiresAt > :now)")
    long countByUser_IdAndStatusNotAndNotExpired(@Param("userId") Long userId,
                                                  @Param("status") UrlStatus status,
                                                  @Param("now") LocalDateTime now);

    @Query("select coalesce(sum(m.clickCount), 0) from UrlMapping m where m.user.id = :userId")
    long sumClickCountByUser_Id(@Param("userId") Long userId);
}
