package com.stockselect.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockSelectOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("stock-select")
                        .description("Screens a stock's quote and option chain for options-selling "
                                + "trade candidates (e.g. Jade Lizard, Bull Put Spread).")
                        .version("0.1.0"));
    }
}
