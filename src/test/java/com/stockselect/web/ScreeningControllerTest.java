package com.stockselect.web;

import com.stockselect.screening.ScreeningService;
import com.stockselect.strategy.TradeCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
                "jade-lizard", "AAPL.US", 193.5, null,
                200.0, 180.0, 175.0, 0.16, -0.15,
                1.5, 5.0, 3.5, 201.5, 178.5);
        when(screeningService.screen("AAPL", "jade-lizard")).thenReturn(List.of(candidate));

        mockMvc.perform(get("/api/screen/jade-lizard/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].strategyName").value("jade-lizard"))
                .andExpect(jsonPath("$[0].symbol").value("AAPL.US"))
                .andExpect(jsonPath("$[0].creditReceived").value(1.5));
    }
}
