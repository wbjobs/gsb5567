package com.gsb.extsort;

/**
 * Thrown when a malformed input line is encountered and the configured
 * bad-line policy is {@code fail}. Always carries the 1-based input line
 * number and a human readable reason.
 */
public final class BadLineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long lineNumber;
    private final String reason;

    public BadLineException(long lineNumber, String reason) {
        super("line " + lineNumber + ": " + reason);
        this.lineNumber = lineNumber;
        this.reason = reason;
    }

    public long lineNumber() {
        return lineNumber;
    }

    public String reason() {
        return reason;
    }
}
