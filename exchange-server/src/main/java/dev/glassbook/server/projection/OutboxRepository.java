package dev.glassbook.server.projection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {

    @Query(value = "select * from outbox where published_at is null order by id limit :limit for update skip locked",
            nativeQuery = true)
    List<OutboxRecord> lockNextBatch(@Param("limit") int limit);

    long countByPublishedAtIsNull();
}
