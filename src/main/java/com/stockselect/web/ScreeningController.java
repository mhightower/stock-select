package com.stockselect.web;

import com.stockselect.screening.ScreeningResult;
import com.stockselect.screening.ScreeningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/screen")
public class ScreeningController {

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @GetMapping("/{strategy}/{symbol}")
    public ScreeningResult screen(@PathVariable String strategy, @PathVariable String symbol) {
        return screeningService.screen(symbol, strategy);
    }
}
