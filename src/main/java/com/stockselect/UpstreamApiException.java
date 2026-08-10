package com.stockselect;

import org.springframework.http.HttpStatusCode;

/** Wraps an HTTP error response from a vendor API (EODHD, MarketData.app, ...) with which vendor it came from. */
public class UpstreamApiException extends RuntimeException {

    private final String vendor;
    private final HttpStatusCode status;

    public UpstreamApiException(String vendor, HttpStatusCode status, Throwable cause) {
        super(vendor + " returned " + status.value() + ": " + cause.getMessage(), cause);
        this.vendor = vendor;
        this.status = status;
    }

    public String vendor() {
        return vendor;
    }

    public HttpStatusCode status() {
        return status;
    }
}
