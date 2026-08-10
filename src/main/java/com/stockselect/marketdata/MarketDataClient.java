package com.stockselect.marketdata;

import com.stockselect.marketdata.dto.OptionsChainResponse;
import com.stockselect.strategy.OptionContract;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Component
public class MarketDataClient {

    private static final ZoneId OPTIONS_EXPIRATION_ZONE = ZoneId.of("America/New_York");
    private static final int CHAIN_WINDOW_START_DAYS = 1;
    private static final int CHAIN_WINDOW_END_DAYS = 75;

    private final WebClient webClient;

    public MarketDataClient(WebClient marketDataWebClient) {
        this.webClient = marketDataWebClient;
    }

    /** Fetches every expiration within a ~1-75 DTE window, wide enough for near/medium-term strategies. */
    public Flux<OptionContract> getOptionsChain(String symbol) {
        LocalDate from = LocalDate.now().plusDays(CHAIN_WINDOW_START_DAYS);
        LocalDate to = LocalDate.now().plusDays(CHAIN_WINDOW_END_DAYS);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/options/chain/{symbol}/")
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build(symbol))
                .retrieve()
                .bodyToMono(OptionsChainResponse.class)
                .flatMapMany(MarketDataClient::toContracts);
    }

    private static Flux<OptionContract> toContracts(OptionsChainResponse response) {
        if (!response.isOk()) {
            return Flux.empty();
        }

        List<OptionContract> contracts = new ArrayList<>(response.size());
        for (int i = 0; i < response.size(); i++) {
            contracts.add(new OptionContract(
                    response.optionSymbol().get(i),
                    response.underlying().get(i),
                    toLocalDate(response.expiration().get(i)),
                    response.side().get(i),
                    response.strike().get(i),
                    response.bid().get(i),
                    response.ask().get(i),
                    response.volume().get(i),
                    response.openInterest().get(i),
                    response.delta().get(i),
                    response.gamma().get(i),
                    response.theta().get(i),
                    response.vega().get(i),
                    response.iv().get(i),
                    response.dte().get(i),
                    response.mid().get(i)
            ));
        }
        return Flux.fromIterable(contracts);
    }

    private static LocalDate toLocalDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(OPTIONS_EXPIRATION_ZONE).toLocalDate();
    }
}
