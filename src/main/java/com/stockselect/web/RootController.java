package com.stockselect.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @GetMapping("/")
    public ApiInfo root() {
        return new ApiInfo("stock-select", "GET /api/screen/{strategy}/{symbol}, e.g. /api/screen/jade-lizard/AAPL");
    }
}
