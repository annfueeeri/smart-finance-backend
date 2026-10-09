package collector.bu.exception;

/** 预算业务错误，不回显 SQL 或其他用户的数据。 */
public class BudgetException extends RuntimeException {
    private final String code;
    /** 保存稳定的业务错误码，供 Controller 统一转换 HTTP 状态。 */
    public BudgetException(String code) { super(code); this.code=code; }
    /** 返回预算校验、归属、配置冲突等业务错误码。 */
    public String code() { return code; }
}
