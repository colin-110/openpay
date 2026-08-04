package com.openpay.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.gateway.config.GatewayProperties;
import com.openpay.security.AuthServiceClient;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Guards the one duplication in this service that has already caused a bug.
 *
 * <p>A proxied prefix has to be written down twice: in {@code openpay.gateway.routes}, which says
 * where it goes, and in {@link ProxyController}'s {@code @RequestMapping}, which says whether Spring
 * hands the request to the proxy at all. They look redundant and are not, and the failure when they
 * disagree is quietly misleading — the gateway answers <strong>404</strong> for a path whose route
 * is configured, whose upstream is healthy, and which therefore looks entirely correct in every
 * config file you would think to check.
 *
 * <p>That is exactly how {@code /api/v1/tokens} behaved when it was added to the configuration and
 * not to the annotation. This test turns that twenty-minute confusion into a failing build.
 *
 * <p>Deliberately one-directional: every configured route must be mapped, but a mapped prefix
 * without a route is allowed to exist — that combination fails loudly and immediately with
 * {@code NoRouteException} rather than pretending the endpoint is absent.
 */
@SpringBootTest
class ProxyMappingCoversEveryRouteTest {

    @Autowired
    private GatewayProperties properties;

    @MockBean
    private AuthServiceClient authServiceClient;

    @Test
    void everyConfiguredRouteIsAlsoDispatchedToTheProxy() {
        Set<String> mapped = Arrays.stream(findMapping().value())
                // "/api/v1/payments/**" and "/api/v1/payments" both stand for the same prefix.
                .map(pattern -> pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 3) : pattern)
                .collect(Collectors.toSet());

        List<String> configured = properties.getRoutes().stream()
                .map(GatewayProperties.Route::getPathPrefix)
                .toList();

        assertThat(configured)
                .as("every prefix in openpay.gateway.routes must appear in ProxyController's "
                        + "@RequestMapping, or the gateway 404s it before the route is ever consulted")
                .isNotEmpty()
                .allSatisfy(prefix -> assertThat(mapped).contains(prefix));
    }

    @Test
    void everyMappedPrefixIsSpelledBothWays() {
        Set<String> patterns = Set.of(findMapping().value());

        // A collection endpoint — POST /api/v1/payments — does not match "/api/v1/payments/**",
        // which requires a trailing segment. Missing the bare spelling breaks payment creation
        // while leaving every read working, which is a confusing way to find out.
        for (String pattern : patterns) {
            if (pattern.endsWith("/**")) {
                assertThat(patterns).contains(pattern.substring(0, pattern.length() - 3));
            } else {
                assertThat(patterns).contains(pattern + "/**");
            }
        }
    }

    private RequestMapping findMapping() {
        return Arrays.stream(ProxyController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(RequestMapping.class))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ProxyController has no @RequestMapping"));
    }
}
