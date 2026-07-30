package com.openpay.gateway.routing;

public class NoRouteException extends RuntimeException {

    public NoRouteException(String path) {
        super("No route configured for " + path);
    }
}
