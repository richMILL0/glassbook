package dev.glassbook.server.projection;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeRepository extends JpaRepository<TradeRecord, Long> {

    @Query("select t from TradeRecord t where t.buyerId = :p or t.sellerId = :p order by t.id desc")
    List<TradeRecord> findMine(@Param("p") long participantId, Pageable pageable);

    List<TradeRecord> findByInstrumentIdOrderByIdDesc(int instrumentId, Pageable pageable);
}
