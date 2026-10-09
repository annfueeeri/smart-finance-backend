package collector.bu.service;

public class UserManagementException extends RuntimeException {
    public enum Reason { USER_NOT_FOUND, LAST_ADMIN }
    private final Reason reason;

    /**
     * 创建带明确原因的业务异常，供统一异常处理器选择响应状态和错误码。
     * @param reason 用户不存在或最后一个管理员不能降级
     */
    public UserManagementException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /**
     * 取得业务失败原因，供 Controller 异常处理器生成对应错误响应。
     * @return 创建异常时记录的原因枚举
     */
    public Reason reason() { return reason; }
}
