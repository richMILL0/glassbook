package dev.glassbook.server.projection;

import dev.glassbook.core.model.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long seq;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "asset_id", nullable = false)
    private int assetId;

    @Column(nullable = false)
    private long delta;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "ref_id", nullable = false)
    private long refId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected LedgerEntryRecord() { }

    public LedgerEntryRecord(Event.Posted p) {
        this.seq = p.seq();
        this.accountId = p.accountId();
        this.assetId = p.assetId();
        this.delta = p.delta();
        this.kind = p.kind().name();
        this.refId = p.refId();
        this.postedAt = Instant.ofEpochMilli(p.timestamp());
    }

    public Long getId() {
        return id;
    }

    public long getSeq() {
        return seq;
    }

    public long getAccountId() {
        return accountId;
    }

    public int getAssetId() {
        return assetId;
    }

    public long getDelta() {
        return delta;
    }

    public String getKind() {
        return kind;
    }

    public long getRefId() {
        return refId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
