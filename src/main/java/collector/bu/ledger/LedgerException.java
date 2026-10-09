package collector.bu.ledger;

/** 收支业务失败，统一转换为不泄露数据库信息的接口错误。 */
public class LedgerException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, ACCOUNT_NOT_FOUND, ACCOUNT_DUPLICATE }
    private final Reason reason;
    /** 保存业务失败原因，不包含账号、密码或原始 SQL。 */
    public LedgerException(Reason reason) { super(reason.name()); this.reason = reason; }
    /** 返回失败原因，供统一异常处理器选择 HTTP 状态码。 */
    public Reason reason() { return reason; }
}
