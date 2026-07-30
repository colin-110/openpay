package com.openpay.gateway.routing;

public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String pathPrefix, Throwable cause) {
        super("No healthy upstream for " + pathPrefix, cause);
    }
}
