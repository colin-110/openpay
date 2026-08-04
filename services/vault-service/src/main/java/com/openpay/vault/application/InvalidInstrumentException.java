package com.openpay.vault.application;

/**
 * A rejection that names the field and never the value.
 *
 * <p>The constructor takes them separately rather than as one message so that it is awkward to
 * write the offending value into it by accident — the shape of the class is the reminder.
 */
public class InvalidInstrumentException extends RuntimeException {

    private final String field;

    public InvalidInstrumentException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
