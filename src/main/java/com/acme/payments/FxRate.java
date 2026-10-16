package com.acme.payments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FxRate(String from, String to, BigDecimal rate) {
}
