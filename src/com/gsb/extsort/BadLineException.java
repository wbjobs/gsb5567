package com.gsb.extsort;

import java.io.IOException;

/**
 * Thrown when an input line does not have enough tab-separated columns to
 * contain the sort key and the tool runs with {@code --bad-line fail}.
 * The message always contains the 1-based input line number and the reason.
 */
public class BadLineException extends IOException {

    private final long lineNumber;

    public BadLineException(long lineNumber, String reason) {
        super("bad line " + lineNumber + ": " + reason);
        this.lineNumber = lineNumber;
    }

    /** 1-based number of the offending input line. */
    public long lineNumber() {
        return lineNumber;
    }
}
