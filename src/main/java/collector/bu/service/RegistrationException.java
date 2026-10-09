package collector.bu.service;

public class RegistrationException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, USERNAME_TAKEN }

    private final Reason reason;

    public RegistrationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
