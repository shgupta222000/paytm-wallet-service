package com.paytm.wallet.exception;

public class TransferAlreadyReversedException extends RuntimeException {
    public TransferAlreadyReversedException(String message) {
        super(message);
    }
}
