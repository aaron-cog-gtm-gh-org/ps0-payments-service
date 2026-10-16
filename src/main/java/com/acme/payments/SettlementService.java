package com.acme.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {
    public static final BigDecimal PROCESSING_FEE = new BigDecimal("2.50");
    public static final BigDecimal FX_FEE_RATE = new BigDecimal("0.0025");

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

        boolean sameCurrency = request.sourceCurrency().equals(request.settlementCurrency());
        BigDecimal rate = sameCurrency ? BigDecimal.ONE
                : fxRateClient.getRate(request.sourceCurrency(), request.settlementCurrency()).rate();
        BigDecimal converted = request.amount().multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal fxFee = sameCurrency ? BigDecimal.ZERO.setScale(2)
                : converted.multiply(FX_FEE_RATE).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal net = converted.subtract(PROCESSING_FEE).subtract(fxFee)
                .setScale(2, RoundingMode.HALF_EVEN);
        if (net.signum() <= 0) {
            throw new IllegalArgumentException("Settlement amount must exceed the fees");
        }

        String narrative = "Settlement " + request.reference() + ": "
                + request.amount().setScale(2, RoundingMode.HALF_EVEN).toPlainString() + " "
                + request.sourceCurrency() + " @ " + rate.toPlainString() + ", fee "
                + PROCESSING_FEE.toPlainString() + " + fx " + fxFee.toPlainString() + " "
                + request.settlementCurrency();
        LedgerPosting posting = new LedgerPosting(request.reference(), request.debitAccount(),
                request.creditAccount(), net, request.settlementCurrency(), narrative);

        LOGGER.info("Settling {} {} to {}: net {} {}, fee {} + fx {} {}",
                request.amount(), request.sourceCurrency(), request.settlementCurrency(), net,
                request.settlementCurrency(), PROCESSING_FEE, fxFee, request.settlementCurrency());
        return ledgerClient.post(posting);
    }
}
