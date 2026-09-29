package io.peek.core.configuration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "demo_configuration")
public class DemoConfigurationEntity {
    @Id public short id;
    @Column(name = "stock_sync_timeout_seconds") public int stockSyncTimeoutSeconds;
    @Column(name = "fiscal_timeout_seconds") public int fiscalTimeoutSeconds;
    @Column(name = "physical_stock_tolerance", precision = 15, scale = 3) public BigDecimal physicalStockTolerance;
    @Column(name = "receipt_tolerance", precision = 15, scale = 3) public BigDecimal receiptTolerance;
    @Column(name = "fiscal_correlation_key") public String fiscalCorrelationKey;
    protected DemoConfigurationEntity() {}
}
