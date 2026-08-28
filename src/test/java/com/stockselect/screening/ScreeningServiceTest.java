package com.stockselect.screening;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stockselect.UpstreamApiException;
import com.stockselect.eodhd.EodhdClient;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.marketdata.MarketDataClient;
import com.stockselect.strategy.OptionContract;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import com.stockselect.strategy.TradeStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

    @Mock
    private EodhdClient eodhdClient;

    @Mock
    private MarketDataClient marketDataClient;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void dispatchesToTheMatchingStrategyWithABareUppercasedSymbol() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(quote));
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.empty());

        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        ScreeningResult result = service.screen("aapl.us", "jade-lizard");

        assertThat(result.warnings()).isEmpty();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.candidates().get(0).underlyingPrice()).isEqualTo(193.5);
    }

    @Test
    void throwsForAnUnknownStrategyName() {
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

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

        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        ScreeningResult result = service.screen("AAPL", "jade-lizard");

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("EODHD");
        assertThat(result.candidates().get(0).underlyingPrice()).isEqualTo(201.75);
    }

    @Test
    void recordsScreenRequestMetricsOnSuccess() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(quote));
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.empty());
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        service.screen("AAPL", "jade-lizard");

        assertThat(meterRegistry.counter("stockselect.screen.requests", "strategy", "jade-lizard", "outcome", "success").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.timer("stockselect.screen.latency", "strategy", "jade-lizard", "outcome", "success").count())
                .isEqualTo(1L);
    }

    @Test
    void recordsAFailureMetricWithAnUnknownStrategySentinelForAnUnknownStrategyName() {
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        assertThatThrownBy(() -> service.screen("AAPL", "iron-condor"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(meterRegistry.counter("stockselect.screen.requests", "strategy", "unknown", "outcome", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordsAFailureMetricWhenMarketDataFails() {
        when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(
                new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31)));
        when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.error(
                new UpstreamApiException("MarketData.app", HttpStatus.TOO_MANY_REQUESTS, new RuntimeException("429"))));
        ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

        assertThatThrownBy(() -> service.screen("AAPL", "jade-lizard"))
                .isInstanceOf(UpstreamApiException.class);

        assertThat(meterRegistry.counter("stockselect.screen.requests", "strategy", "jade-lizard", "outcome", "failure").count())
                .isEqualTo(1.0);
    }

    @Test
    void logsStructuredFieldsOnScreenCompletion() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(ScreeningService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            when(eodhdClient.getQuote("AAPL")).thenReturn(Mono.just(
                    new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31)));
            when(marketDataClient.getOptionsChain("AAPL")).thenReturn(Flux.empty());
            ScreeningService service = new ScreeningService(eodhdClient, marketDataClient, List.of(new StubStrategy("jade-lizard")), meterRegistry);

            service.screen("AAPL", "jade-lizard");

            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)));
            assertThat(fields).containsEntry("strategy", "jade-lizard");
            assertThat(fields).containsEntry("symbol", "AAPL");
            assertThat(fields).containsEntry("status", "success");
            assertThat(fields).containsKey("latencyMs");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }

    private record StubStrategy(String name) implements TradeStrategy {
        @Override
        public List<TradeCandidate> evaluate(StrategyContext context) {
            return List.of(new TradeCandidate(
                    name, context.symbol(), "USD", context.underlyingPrice(), null,
                    null, null, null, null, null, null, 0, null, null, null, null, null));
        }
    }
}
