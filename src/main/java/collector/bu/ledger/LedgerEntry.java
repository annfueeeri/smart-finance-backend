package collector.bu.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 一条真实收支记录，金额为正数，收支方向单独保存，币种在记录创建时固定。 */
public record LedgerEntry(long id, String kind, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount, String currency, LocalDate date,
        long accountId, String accountName, String category, String merchant, String note,
        LocalDateTime createdAt, String createdBy, LocalDateTime updatedAt, String updatedBy, boolean deleted) { }
