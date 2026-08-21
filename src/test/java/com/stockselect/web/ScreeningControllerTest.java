package com.stockselect.web;

import com.stockselect.UpstreamApiException;
import com.stockselect.screening.ScreeningResult;
import com.stockselect.screening.ScreeningService;
import com.stockselect.strategy.TradeCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScreeningController.class)
class ScreeningControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScreeningService screeningService;

    @Test
    void returnsCandidatesFromTheService() throws Exception {
        TradeCandidate candidate = new TradeCandidate(
                "jade-lizard", "AAPL.US", "USD", 193.5, null,
                200.0, null, 180.0, 175.0, 0.16, -0.15,
                1.5, 5.0, 3.5, 201.5, 178.5);
        when(screeningService.screen("AAPL", "jade-lizard"))
                .thenReturn(new ScreeningResult(List.of(candidate), List.of()));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].strategyName").value("jade-lizard"))
                .andExpect(jsonPath("$.candidates[0].symbol").value("AAPL.US"))
                .andExpect(jsonPath("$.candidates[0].currency").value("USD"))
                .andExpect(jsonPath("$.candidates[0].creditReceived").value(1.5))
                .andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void includesWarningsWhenEodhdDegradedGracefully() throws Exception {
        TradeCandidate candidate = new TradeCandidate(
                "jade-lizard", "AAPL", "USD", 201.75, null,
                200.0, null, 180.0, 175.0, 0.16, -0.15,
                1.5, 5.0, 3.5, 201.5, 178.5);
        when(screeningService.screen("AAPL", "jade-lizard")).thenReturn(new ScreeningResult(
                List.of(candidate), List.of("EODHD unavailable (...); using MarketData.app's price instead.")));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].underlyingPrice").value(201.75))
                .andExpect(jsonPath("$.warnings[0]").value("EODHD unavailable (...); using MarketData.app's price instead."));
    }

    @Test
    void returnsBadRequestForAnUnknownStrategy() throws Exception {
        when(screeningService.screen("AAPL", "iron-condor"))
                .thenThrow(new IllegalArgumentException("Unknown strategy: iron-condor"));

        mockMvc.perform(get("/api/screen/iron-condor/AAPL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown strategy: iron-condor"));
    }

    @Test
    void returnsTooManyRequestsWhenAVendorRateLimitsUs() throws Exception {
        when(screeningService.screen("AAPL", "jade-lizard"))
                .thenThrow(new UpstreamApiException("MarketData.app", HttpStatus.TOO_MANY_REQUESTS,
                        new RuntimeException("429 Too Many Requests")));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("MarketData.app rate limit exceeded — try again later."));
    }

    @Test
    void returnsBadGatewayWhenAVendorRejectsTheApiKey() throws Exception {
        when(screeningService.screen("AAPL", "jade-lizard"))
                .thenThrow(new UpstreamApiException("EODHD", HttpStatus.FORBIDDEN,
                        new RuntimeException("403 Forbidden")));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("EODHD rejected the request — check the API key and plan entitlements."));
    }
}
