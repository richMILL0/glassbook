package dev.glassbook.server.projection;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<OrderRecord, Long> {

    List<OrderRecord> findByParticipantIdOrderByIdDesc(long participantId, Pageable pageable);

    List<OrderRecord> findByParticipantIdAndStatusInOrderByIdDesc(long participantId,
                                                                   Collection<OrderStatus> statuses,
                                                                   Pageable pageable);
}
