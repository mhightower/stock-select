package com.stockselect.screening;

import com.stockselect.eodhd.EodhdClient;
import com.stockselect.eodhd.dto.Quote;
import com.stockselect.strategy.StrategyContext;
import com.stockselect.strategy.TradeCandidate;
import com.stockselect.strategy.TradeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

    @Mock
    private EodhdClient eodhdClient;

    @Test
    void dispatchesToTheMatchingStrategyWithAnUppercasedSymbol() {
        Quote quote = new Quote("AAPL.US", 0L, 190, 195, 189, 193.5, 1_000_000, 191, 2.5, 1.31);
        when(eodhdClient.getQuote("AAPL.US")).thenReturn(Mono.just(quote));
        when(eodhdClient.getOptionsChain("AAPL.US")).thenReturn(Flux.empty());

        ScreeningService service = new ScreeningService(eodhdClient, List.of(new StubStrategy("jade-lizard")));

        List<TradeCandidate> result = service.screen("aapl.us", "jade-lizard");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL.US");
        assertThat(result.get(0).underlyingPrice()).isEqualTo(193.5);
    }

    @Test
    void throwsForAnUnknownStrategyName() {
        ScreeningService service = new ScreeningService(eodhdClient, List.of(new StubStrategy("jade-lizard")));

        assertThatThrownBy(() -> service.screen("AAPL", "iron-condor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iron-condor");
    }

    private record StubStrategy(String name) implements TradeStrategy {
        @Override
        public List<TradeCandidate> evaluate(StrategyContext context) {
            return List.of(new TradeCandidate(
                    name, context.symbol(), context.quote().close(), null,
                    null, null, null, null, null, 0, null, null, null, null));
        }
    }
}
