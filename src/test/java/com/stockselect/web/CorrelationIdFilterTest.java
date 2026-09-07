package com.stockselect.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesARequestIdWhenNoneIsSupplied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        assertThat(generated).isNotBlank();
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(generated);
    }

    @Test
    void echoesAnInboundRequestIdUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "caller-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isEqualTo("caller-supplied-id");
        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("caller-supplied-id");
    }

    @Test
    void treatsABlankInboundHeaderAsAbsentAndGeneratesAnId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.REQUEST_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER)).isNotEqualTo("   ");
    }
}
