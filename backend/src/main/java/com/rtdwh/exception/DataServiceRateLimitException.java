package com.rtdwh.exception;
public class DataServiceRateLimitException extends RuntimeException {
    public DataServiceRateLimitException(String message) { super(message); }
}
