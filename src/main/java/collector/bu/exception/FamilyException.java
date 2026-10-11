package collector.bu.exception;

/** 家庭参数、邀请和成员管理的业务错误，不回显原始邀请码。 */
public class FamilyException extends RuntimeException {
    private final String code;
    /** 保存可公开的错误码，供统一异常处理返回。 */
    public FamilyException(String code) { super(code); this.code=code; }
    /** 返回家庭业务错误码。 */
    public String code() { return code; }
}
