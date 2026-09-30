package dev.glassbook.server.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "projection_cursor")
public class ProjectionCursor {

    @Id
    private Integer id;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    protected ProjectionCursor() { }

    public ProjectionCursor(Integer id, long lastSeq) {
        this.id = id;
        this.lastSeq = lastSeq;
    }

    public Integer getId() {
        return id;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }
}
