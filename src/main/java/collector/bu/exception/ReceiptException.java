package collector.bu.exception;

/** 收据上传、归属及重复错误，不回显文件内容。 */
public class ReceiptException extends RuntimeException {
    private final String code;
    /** 保存可公开的业务错误码。 */
    public ReceiptException(String code) { super(code);this.code=code; }
    /** 返回供前端区分错误的业务码。 */
    public String code() { return code; }
}
