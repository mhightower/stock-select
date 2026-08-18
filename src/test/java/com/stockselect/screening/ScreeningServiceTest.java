package com.stockselect.screening;

import com.stockselect.UpstreamApiException;
import com.stockselect.eodhd.EodhdClient;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.marketdata.MarketDataClient;
import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import com.stockselect.strategy.TradeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

    @Mock
    private EodhdClient eodhdClient;

    @Mock
    private MarketDataClient marketDataClient;

    @Test
    void dispatchesToTheMatchingStrategyWithABareUppercasedSymbol() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(quote));
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.empty());

        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")));

        ScreeningResult result = service.screen("aapl.us", "jade-lizard");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.candidates().get(0).underlyingPrice()).isEqualTo(193.5);
    }

    @Test
    void throwsForAnUnknownStrategyName() {
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")));

        assertThatThrownBy(() -> service.screen("AAPL", "iron-condor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iron-condor");
    }

    @Test
    void fallsBackToMarketDataPriceAndWarnsWhenEodhdIsUnavailable() {
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.error(
                new UpstreamApiException("EODHD", HttpStatus.TOO_MANY_REQUESTS, new RuntimeException("429"))));
        OptionContract contract = new OptionContract(
                "AAPL260918C00110000", "AAPL", LocalDate.now().plusDays(45), "call",
                110.0, 201.75, 1.00, 1.10, 100L, 500L,
                0.16, 0.05, -0.02, 0.10, 0.25, 45, 1.05);
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.just(contract));

        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")));

        ScreeningResult result = service.screen("AAPL", "jade-lizard");

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("EODHD");
        assertThat(result.candidates().get(0).underlyingPrice()).isEqualTo(201.75);
    }

    private record StubStrategy(String name) implements TradeStrategy {
        @Override
        public List<TradeCandidate> evaluate(StrategyContext context) {
            return List.of(new TradeCandidate(
                    name, context.symbol(), "USD", context.underlyingPrice(), null,
                    null, null, null, null, null, 0, null, null, null, null));
        }
    }
}
