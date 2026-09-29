package io.peek.core.exceptions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exception_evidence")
public class EvidenceEntity {
    @Id public UUID id;
    @Column(name = "exception_id") public UUID exceptionId;
    @Column(name = "event_id") public UUID eventId;
    @Column(name = "evidence_type") public String type;
    public String source;
    public String label;
    @Column(columnDefinition = "text") public String value;
    @Column(name = "occurred_at") public Instant occurredAt;
    public int position;
    protected EvidenceEntity() {}
}
