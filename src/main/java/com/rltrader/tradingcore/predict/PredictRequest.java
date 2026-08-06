package com.rltrader.tradingcore.predict;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictRequest(
        String symbol,
        Portfolio portfolio,
        @JsonProperty("as_of") String asOf) {

    public record Portfolio(
            @JsonProperty("units_held") int unitsHeld,
            @JsonProperty("shares_held") double sharesHeld,
            @JsonProperty("bars_since_entry") int barsSinceEntry,
            @JsonProperty("available_cash") double availableCash) {
    }

    public static PredictRequest flat(String symbol, double cash, String asOf) {
        return new PredictRequest(symbol, new Portfolio(0, 0.0, 0, cash), asOf);
    }
}
