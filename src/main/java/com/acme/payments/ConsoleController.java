package com.acme.payments;

import com.acme.commons.http.AcmeHttpException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console/api")
public class ConsoleController {
    private final FxRateClient fxRateClient;
    private final LedgerClient ledgerClient;

    public ConsoleController(FxRateClient fxRateClient, LedgerClient ledgerClient) {
        this.fxRateClient = fxRateClient;
        this.ledgerClient = ledgerClient;
    }

    @GetMapping("/rates/{from}/{to}")
    public FxRate rate(@PathVariable String from, @PathVariable String to) {
        return fxRateClient.getRate(from, to);
    }

    @GetMapping("/accounts/{id}/balance")
    public Map<String, Object> balance(@PathVariable String id) {
        return ledgerClient.balance(id);
    }

    @ExceptionHandler(AcmeHttpException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamHttpFailure(AcmeHttpException exception) {
        String message = exception.getMessage() == null
                ? "Upstream service request failed"
                : exception.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", message, "upstreamStatus", exception.status()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> handleUpstreamConnectionFailure(UncheckedIOException exception) {
        String message = exception.getMessage() == null
                ? "Upstream service is unavailable"
                : exception.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
    }
}
