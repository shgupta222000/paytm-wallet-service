package com.paytm.wallet.exception;

import com.paytm.wallet.config.RequestLoggingFilter;
import com.paytm.wallet.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        log.warn("declined_insufficient_funds correlation_id={} message={}", correlationId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("INSUFFICIENT_FUNDS", ex.getMessage(), correlationId));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        log.warn("idempotency_conflict_409 correlation_id={} message={}", correlationId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_CONFLICT", ex.getMessage(), correlationId));
    }

    @ExceptionHandler(TransferAlreadyReversedException.class)
    public ResponseEntity<ErrorResponse> handleTransferAlreadyReversed(TransferAlreadyReversedException ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        log.warn("transfer_already_reversed correlation_id={} message={}", correlationId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ALREADY_REVERSED", ex.getMessage(), correlationId));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        log.warn("resource_not_found correlation_id={} message={}", correlationId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage(), correlationId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        log.warn("bad_request correlation_id={} message={}", correlationId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage(), correlationId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        log.warn("validation_failed correlation_id={} details={}", correlationId, details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", details, correlationId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        String correlationId = MDC.get(RequestLoggingFilter.MDC_CORRELATION_ID_KEY);
        log.error("internal_server_error correlation_id={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred", correlationId));
    }
}
