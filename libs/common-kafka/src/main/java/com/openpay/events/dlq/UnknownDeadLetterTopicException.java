package com.openpay.events.dlq;

import java.util.List;

/** A topic this service was not configured to replay. */
public class UnknownDeadLetterTopicException extends RuntimeException {

    public UnknownDeadLetterTopicException(String requested, List<String> allowed) {
        super("'" + requested + "' is not a dead-letter topic this service handles. Known: " + allowed);
    }
}
