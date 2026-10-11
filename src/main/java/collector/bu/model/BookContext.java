package collector.bu.model;

import java.math.BigDecimal;

/** 服务端授权后的账本上下文；id 是账本主键，不能当作操作者用户 ID。 */
public record BookContext(long id, String currency, String timezone, BigDecimal monthlyBudget) { }
