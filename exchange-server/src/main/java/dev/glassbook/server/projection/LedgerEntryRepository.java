package dev.glassbook.server.projection;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryRecord, Long> {

    interface AssetSum {
        Integer getAssetId();

        Long getTotal();
    }

    @Query("select e.assetId as assetId, sum(e.delta) as total from LedgerEntryRecord e "
            + "group by e.assetId order by e.assetId")
    List<AssetSum> sumByAsset();

    List<LedgerEntryRecord> findByAccountIdOrderByIdDesc(long accountId, Pageable pageable);
}
