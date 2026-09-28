package io.reimu.core;

/** Public errors contain no credential, stack trace, or SDK state. */
public final class Fault extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final String code;
    public final int status;
    public Fault(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static void require(boolean ok, int status, String code, String message) {
        if (!ok) throw new Fault(status,code,message);
    }
}
