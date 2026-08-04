package com.openpay.storefront;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storefront")
public class StorefrontProperties {

    /** Where this shop sends payments. The public gateway, exactly as a real merchant would. */
    private String gatewayBaseUrl = "http://localhost:8080";

    /**
     * The merchant API key this shop pays with.
     *
     * <p>Read from the environment and <strong>never sent to the browser</strong>. That is the one
     * design rule this service has: a real gateway is integrated from a merchant's server, because
     * a key in a page's JavaScript is a key every visitor can create and refund payments with. The
     * browser here only ever learns a payment id.
     *
     * <p>Empty by default, and the shop says so on its front page rather than failing obscurely —
     * {@code scripts/seed-demo.sh} prints a key to use.
     */
    private String apiKey = "";

    /**
     * The shop's <em>publishable</em> key, and the one credential here that is meant to be public.
     *
     * <p>It goes into the page, on purpose. It can mint a payment token and do nothing else — not
     * create a payment, not read one, not refund one — so a visitor who copies it out of the
     * developer tools has gained the ability to type their own card number into a form, which they
     * already had.
     *
     * <p>Contrast {@link #apiKey} directly above, which must never reach a browser and which is the
     * reason these are two separate credentials rather than one.
     */
    private String publishableKey = "";

    /**
     * The gateway address as the <em>browser</em> must reach it, which is not the same value as
     * {@link #gatewayBaseUrl}.
     *
     * <p>That one is resolved inside the network — {@code http://gateway-service:8080} in compose —
     * and a browser cannot resolve it at all. Tokenisation is the one call the customer's browser
     * makes to the platform directly, so it needs the public address, and conflating the two is a
     * checkout that works in every test and fails for every real visitor.
     */
    private String gatewayPublicUrl = "http://localhost:8080";

    /** Where to point the "view this in the dashboard" link. */
    private String dashboardUrl = "http://localhost:5173";

    /**
     * The demo merchant's dashboard login, shown on the shop's own page.
     *
     * <p>Displaying a password is not something to do casually, so: this account exists only for the
     * demo, is generated per deployment rather than shared, owns nothing but payments the visitor
     * made themselves, and the alternative is a merchant dashboard nobody can open — which removes
     * the half of the story where the merchant sees the money arrive.
     *
     * <p>Empty unless a demo provisioner filled them in, so a real deployment of this shop shows
     * nothing at all here.
     */
    private String dashboardEmail = "";

    private String dashboardPassword = "";

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(10);

    public String getGatewayBaseUrl() {
        return gatewayBaseUrl;
    }

    public void setGatewayBaseUrl(String gatewayBaseUrl) {
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getPublishableKey() {
        return publishableKey;
    }

    public void setPublishableKey(String publishableKey) {
        this.publishableKey = publishableKey;
    }

    public String getGatewayPublicUrl() {
        return gatewayPublicUrl;
    }

    public void setGatewayPublicUrl(String gatewayPublicUrl) {
        this.gatewayPublicUrl = gatewayPublicUrl;
    }

    public String getDashboardUrl() {
        return dashboardUrl;
    }

    public void setDashboardUrl(String dashboardUrl) {
        this.dashboardUrl = dashboardUrl;
    }

    public String getDashboardEmail() {
        return dashboardEmail;
    }

    public void setDashboardEmail(String dashboardEmail) {
        this.dashboardEmail = dashboardEmail;
    }

    public String getDashboardPassword() {
        return dashboardPassword;
    }

    public void setDashboardPassword(String dashboardPassword) {
        this.dashboardPassword = dashboardPassword;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Both keys, not either. The secret key alone would leave a checkout that cannot tokenise, and
     * the publishable key alone a shop that cannot take the payment afterwards — and both fail at
     * the point the customer has already typed their card in, which is the worst place to discover
     * a missing environment variable.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && publishableKey != null && !publishableKey.isBlank();
    }
}
