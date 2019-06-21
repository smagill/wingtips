package com.nike.wingtips.componenttest;

import com.nike.wingtips.Span;
import com.nike.wingtips.Span.SpanPurpose;
import com.nike.wingtips.TraceHeaders;
import com.nike.wingtips.Tracer;
import com.nike.wingtips.apache.httpclient.WingtipsApacheHttpClientInterceptor;
import com.nike.wingtips.apache.httpclient.WingtipsHttpClientBuilder;
import com.nike.wingtips.apache.httpclient.tag.ApacheHttpClientTagAdapter;
import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.nike.wingtips.servlet.RequestTracingFilter;
import com.nike.wingtips.tags.KnownZipkinTags;
import com.nike.wingtips.tags.WingtipsTags;
import com.nike.wingtips.tags.ZipkinHttpTagStrategy;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import static com.nike.wingtips.componenttest.ApacheHttpClientWithWingtipsComponentTest.TestBackendServer.ENDPOINT_PATH;
import static com.nike.wingtips.componenttest.ApacheHttpClientWithWingtipsComponentTest.TestBackendServer.ENDPOINT_PAYLOAD;
import static com.nike.wingtips.componenttest.ApacheHttpClientWithWingtipsComponentTest.TestBackendServer.SLEEP_TIME_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component test validating Wingtips' integration with Apache {@link HttpClient}. This launches a real running server
 * on a random port and sets up Wingtips-instrumented Apache {@link HttpClient}s and fires requests through them at the
 * server to verify the integration.
 *
 * <p>There are tests covering {@link WingtipsHttpClientBuilder} and {@link WingtipsApacheHttpClientInterceptor}.
 *
 * @author Nic Munroe
 */
@RunWith(DataProviderRunner.class)
public class ApacheHttpClientWithWingtipsComponentTest {

    private static final int SERVER_PORT = findFreePort();
    private static ConfigurableApplicationContext serverAppContext;

    private SpanRecorder spanRecorder;

    @BeforeClass
    public static void beforeClass() throws Exception {
        serverAppContext = SpringApplication.run(TestBackendServer.class, "--server.port=" + SERVER_PORT);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        SpringApplication.exit(serverAppContext);
    }

    private static int findFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void beforeMethod() {
        resetTracing();

        spanRecorder = new SpanRecorder();
        Tracer.getInstance().addSpanLifecycleListener(spanRecorder);
    }

    @After
    public void afterMethod() {
        resetTracing();
    }

    private void resetTracing() {
        MDC.clear();
        Tracer.getInstance().unregisterFromThread();
        Tracer.getInstance().removeAllSpanLifecycleListeners();
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void verify_HttpClient_from_WingtipsHttpClientBuilder_traced_correctly(
        boolean spanAlreadyExistsBeforeCall, boolean subspanOptionOn
    ) throws IOException {

        // given
        Span parent = null;
        if (spanAlreadyExistsBeforeCall) {
            parent = Tracer.getInstance().startRequestWithRootSpan("somePreexistingParentSpan");
        }

        String pathTemplateForTags = "/some/path/template/" + UUID.randomUUID().toString();

        WingtipsHttpClientBuilder builder = WingtipsHttpClientBuilder.create(
            subspanOptionOn,
            ZipkinHttpTagStrategy.getDefaultInstance(),
            new ApacheHttpClientTagAdapterWithHttpRouteKnowledge(pathTemplateForTags)
        );
        HttpClient httpClient = builder.build();

        // We always expect at least one span to be completed as part of the call: the server span.
        //      We may or may not have a second span completed depending on the value of subspanOptionOn.
        int expectedNumSpansCompleted = (subspanOptionOn) ? 2 : 1;

        String fullRequestUrl = "http://localhost:" + SERVER_PORT + ENDPOINT_PATH + "?foo=bar";

        // when
        HttpResponse response = httpClient.execute(
            new HttpGet(fullRequestUrl)
        );

        // then
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        assertThat(responsePayloadToString(response)).isEqualTo(ENDPOINT_PAYLOAD);
        verifySpansCompletedAndReturnedInResponse(
            response, SLEEP_TIME_MILLIS, expectedNumSpansCompleted, parent, subspanOptionOn
        );

        if (subspanOptionOn) {
            verifySpanNameAndTags(
                findApacheHttpClientSpanFromCompletedSpans(),
                "GET " + pathTemplateForTags,
                "GET",
                ENDPOINT_PATH,
                fullRequestUrl,
                pathTemplateForTags,
                response.getStatusLine().getStatusCode(),
                "apache.httpclient"
            );
        }

        if (parent != null) {
            parent.close();
        }
    }

    private Span findApacheHttpClientSpanFromCompletedSpans() {
        List<Span> httpClientSpans = spanRecorder.completedSpans
            .stream()
            .filter(s -> "apache.httpclient".equals(s.getTags().get(WingtipsTags.SPAN_HANDLER)))
            .collect(Collectors.toList());

        assertThat(httpClientSpans)
            .withFailMessage(
                "Expected to find exactly one Span that came from Apache HttpClient - instead found: "
                + httpClientSpans.size()
            )
            .hasSize(1);

        return httpClientSpans.get(0);
    }

    private void verifySpanNameAndTags(
        Span span,
        String expectedSpanName,
        String expectedHttpMethodTagValue,
        String expectedPathTagValue,
        String expectedUrlTagValue,
        String expectedHttpRouteTagValue,
        int expectedStatusCodeTagValue,
        String expectedSpanHandlerTagValue
    ) {
        assertThat(span.getSpanName()).isEqualTo(expectedSpanName);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_METHOD)).isEqualTo(expectedHttpMethodTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_PATH)).isEqualTo(expectedPathTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_URL)).isEqualTo(expectedUrlTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_ROUTE)).isEqualTo(expectedHttpRouteTagValue);
        assertThat(span.getTags().get(KnownZipkinTags.HTTP_STATUS_CODE))
            .isEqualTo(String.valueOf(expectedStatusCodeTagValue));
        assertThat(span.getTags().get(WingtipsTags.SPAN_HANDLER)).isEqualTo(expectedSpanHandlerTagValue);
    }

    @DataProvider(value = {
        "true   |   true",
        "true   |   false",
        "false  |   true",
        "false  |   false"
    }, splitBy = "\\|")
    @Test
    public void verify_HttpClient_with_WingtipsApacheHttpClientInterceptor_traced_correctly(
        boolean spanAlreadyExistsBeforeCall, boolean subspanOptionOn
    ) throws IOException {

        // given
        String pathTemplateForTags = "/some/path/template/" + UUID.randomUUID().toString();

        WingtipsApacheHttpClientInterceptor interceptor = new WingtipsApacheHttpClientInterceptor(
            subspanOptionOn,
            ZipkinHttpTagStrategy.getDefaultInstance(),
            new ApacheHttpClientTagAdapterWithHttpRouteKnowledge(pathTemplateForTags)
        );

        Span parent = null;
        if (spanAlreadyExistsBeforeCall) {
            parent = Tracer.getInstance().startRequestWithRootSpan("somePreexistingParentSpan");
        }

        HttpClient httpClient = HttpClientBuilder
            .create()
            .addInterceptorFirst((HttpRequestInterceptor) interceptor)
            .addInterceptorLast((HttpResponseInterceptor) interceptor)
            .build();

        // We always expect at least one span to be completed as part of the call: the server span.
        //      We may or may not have a second span completed depending on the value of subspanOptionOn.
        int expectedNumSpansCompleted = (subspanOptionOn) ? 2 : 1;

        String fullRequestUrl = "http://localhost:" + SERVER_PORT + ENDPOINT_PATH;

        // when
        HttpResponse response = httpClient.execute(new HttpGet(fullRequestUrl));

        // then
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        assertThat(responsePayloadToString(response)).isEqualTo(ENDPOINT_PAYLOAD);
        verifySpansCompletedAndReturnedInResponse(
            response, SLEEP_TIME_MILLIS, expectedNumSpansCompleted, parent, subspanOptionOn
        );

        if (subspanOptionOn) {
            verifySpanNameAndTags(
                findApacheHttpClientSpanFromCompletedSpans(),
                "GET " + pathTemplateForTags,
                "GET",
                ENDPOINT_PATH,
                fullRequestUrl,
                pathTemplateForTags,
                response.getStatusLine().getStatusCode(),
                "apache.httpclient"
            );
        }

        if (parent != null) {
            parent.close();
        }
    }

    private String responsePayloadToString(HttpResponse response) {
        try {
            return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void verifySpansCompletedAndReturnedInResponse(
        HttpResponse response,
        long expectedMinSpanDurationMillis,
        int expectedNumSpansCompleted,
        Span expectedUpstreamSpan,
        boolean expectSubspanFromHttpClient
    ) {
        // We can have a race condition where the response is sent and we try to verify here before the servlet filter
        //      has had a chance to complete the span. Wait a few milliseconds to give the servlet filter time to
        //      finish.
        waitUntilSpanRecorderHasExpectedNumSpans(expectedNumSpansCompleted);

        assertThat(spanRecorder.completedSpans).hasSize(expectedNumSpansCompleted);
        String traceIdFromResponse = response.getFirstHeader(TraceHeaders.TRACE_ID).getValue();
        assertThat(traceIdFromResponse).isNotNull();

        spanRecorder.completedSpans.forEach(
            completedSpan -> assertThat(completedSpan.getTraceId()).isEqualTo(traceIdFromResponse)
        );

        // We also have a race condition where the inner (child) span might claim a longer duration, simply because the
        //      client and server spans are async and we don't control when the spans are completed. So we can't
        //      rely on duration to find outer vs. inner span. Instead we'll look for the expected CLIENT or SERVER
        //      span purpose, depending on what's expected.
        SpanPurpose expectedOutermostSpanPurpose = (expectSubspanFromHttpClient)
                                                   ? SpanPurpose.CLIENT
                                                   : SpanPurpose.SERVER;

        Span outermostSpan = spanRecorder.completedSpans
            .stream()
            .filter(s -> s.getSpanPurpose() == expectedOutermostSpanPurpose)
            .findFirst().get();
        assertThat(TimeUnit.NANOSECONDS.toMillis(outermostSpan.getDurationNanos()))
            .isGreaterThanOrEqualTo(expectedMinSpanDurationMillis);
        
        if (expectedUpstreamSpan == null) {
            assertThat(outermostSpan.getParentSpanId()).isNull();
        }
        else {
            assertThat(outermostSpan.getTraceId()).isEqualTo(expectedUpstreamSpan.getTraceId());
            assertThat(outermostSpan.getParentSpanId()).isEqualTo(expectedUpstreamSpan.getSpanId());
        }
    }

    private void waitUntilSpanRecorderHasExpectedNumSpans(int expectedNumSpans) {
        long timeoutMillis = 5000;
        long startTimeMillis = System.currentTimeMillis();
        while (spanRecorder.completedSpans.size() < expectedNumSpans) {
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            long timeSinceStart = System.currentTimeMillis() - startTimeMillis;
            if (timeSinceStart > timeoutMillis) {
                throw new RuntimeException(
                    "spanRecorder did not have the expected number of spans after waiting "
                    + timeoutMillis + " milliseconds"
                );
            }
        }
    }

    @SuppressWarnings("WeakerAccess")
    private static class SpanRecorder implements SpanLifecycleListener {

        public final List<Span> completedSpans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void spanStarted(Span span) {
        }

        @Override
        public void spanSampled(Span span) {
        }

        @Override
        public void spanCompleted(Span span) {
            completedSpans.add(span);
        }
    }

    @SpringBootApplication
    public static class TestBackendServer {

        public static final String ENDPOINT_PATH = "/foo";
        public static final String ENDPOINT_PAYLOAD = "endpoint-payload-" + UUID.randomUUID().toString();
        public static final long SLEEP_TIME_MILLIS = 100;

        @Bean
        public RequestTracingFilter requestTracingFilter() {
            return new RequestTracingFilter();
        }

        @RestController
        @RequestMapping("/")
        public static class Controller {

            @GetMapping(path = ENDPOINT_PATH)
            @SuppressWarnings("unused")
            public String basicEndpoint(HttpServletRequest request) throws InterruptedException {
                String queryString = request.getQueryString();
                Thread.sleep(SLEEP_TIME_MILLIS);
                return ENDPOINT_PAYLOAD;
            }

        }

    }

    static class ApacheHttpClientTagAdapterWithHttpRouteKnowledge extends ApacheHttpClientTagAdapter {

        private final String httpRouteForAllRequests;

        ApacheHttpClientTagAdapterWithHttpRouteKnowledge(String httpRouteForAllRequests) {
            this.httpRouteForAllRequests = httpRouteForAllRequests;
        }

        @Override
        public @Nullable String getRequestUriPathTemplate(
            @Nullable HttpRequest request, @Nullable HttpResponse response
        ) {
            return httpRouteForAllRequests;
        }
    }
}
