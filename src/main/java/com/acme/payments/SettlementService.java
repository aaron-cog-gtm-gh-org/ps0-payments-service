package com.acme.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {
    public static final BigDecimal SETTLEMENT_FEE = new BigDecimal("2.50");

    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementService.class);

    private final FxRateClient fxRateClient;
    private final LedgerClient ledgerClient;

    public SettlementService(FxRateClient fxRateClient, LedgerClient ledgerClient) {
        this.fxRateClient = fxRateClient;
        this.ledgerClient = ledgerClient;
    }

    public PostingReceipt settle(SettlementRequest request) {
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("Settlement amount must be greater than zero");
        }

        BigDecimal rate = request.sourceCurrency().equals(request.settlementCurrency())
                ? BigDecimal.ONE
                : fxRateClient.getRate(request.sourceCurrency(), request.settlementCurrency()).rate();
        BigDecimal converted = request.amount().multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal net = converted.subtract(SETTLEMENT_FEE).setScale(2, RoundingMode.HALF_EVEN);
        if (net.signum() <= 0) {
            throw new IllegalArgumentException("Settlement amount must exceed the fee");
        }

        String narrative = "Settlement " + request.reference() + ": "
                + request.amount().setScale(2, RoundingMode.HALF_EVEN).toPlainString() + " "
                + request.sourceCurrency() + " @ " + rate.toPlainString() + ", fee "
                + SETTLEMENT_FEE.toPlainString() + " " + request.settlementCurrency();
        LedgerPosting posting = new LedgerPosting(request.reference(), request.debitAccount(),
                request.creditAccount(), net, request.settlementCurrency(), narrative);

        LOGGER.info("Settling {} {} to {}: net {} {}, fee {} {}",
                request.amount(), request.sourceCurrency(), request.settlementCurrency(), net,
                request.settlementCurrency(), SETTLEMENT_FEE, request.settlementCurrency());
        return ledgerClient.post(posting);
    }
}
