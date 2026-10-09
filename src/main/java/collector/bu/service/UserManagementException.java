package collector.bu.service;

public class UserManagementException extends RuntimeException {
    public enum Reason { USER_NOT_FOUND, LAST_ADMIN }
    private final Reason reason;

    public UserManagementException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
