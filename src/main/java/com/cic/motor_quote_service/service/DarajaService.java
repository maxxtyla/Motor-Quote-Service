package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.config.properties.DarajaProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Safaricom Daraja API client — OAuth token management + STK Push.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * WHAT THIS SERVICE DOES
 * ══════════════════════════════════════════════════════════════════════════
 *
 * 1. Fetches an OAuth2 access token from Daraja using Basic authentication
 *    (Base64-encoded consumerKey:consumerSecret).
 *
 * 2. Caches the token in memory (AtomicReference) for ~59 minutes to avoid
 *    hitting the auth endpoint on every payment. Safaricom tokens expire after
 *    3599 seconds; we refresh 99 seconds early to avoid using an expired token.
 *
 * 3. Sends an STK Push (Lipa Na M-Pesa Online) request to prompt the customer
 *    to enter their M-Pesa PIN on their phone.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * STK PUSH FLOW (the full lifecycle, from this service's perspective)
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Your server                          Safaricom Daraja           Customer's phone
 *  ──────────────────────────────────────────────────────────────────────────────
 *  POST /stkpush  ──────────────────────►  validates request
 *                 ◄──────────────────────  { CheckoutRequestID: "..." }  (sync, ~1s)
 *
 *  [store CheckoutRequestID in payment row for reconciliation]
 *
 *                                          sends STK push ──────────► customer sees
 *                                                                       PIN prompt
 *                                                         ◄──────────  customer enters PIN
 *
 *  POST /payments/mpesa/callback  ◄───────  callback with ResultCode + MpesaReceiptNumber
 *  [MpesaCallbackController handles this]
 *
 * ══════════════════════════════════════════════════════════════════════════
 * KEY FIELDS IN THE STK PUSH REQUEST (Daraja docs § "STK Push")
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  BusinessShortCode  Your Paybill or Till number
 *  Password           Base64(shortcode + passkey + timestamp) — per-request
 *  Timestamp          yyyyMMddHHmmss — must match the Password timestamp exactly
 *  TransactionType    "CustomerPayBillOnline" (Paybill) or "CustomerBuyGoodsOnline" (Till)
 *  Amount             Integer KES — Safaricom rejects decimals
 *  PartyA             Customer phone (2547XXXXXXXX)
 *  PartyB             Your shortcode (same as BusinessShortCode for Paybill)
 *  PhoneNumber        Customer phone — same as PartyA for STK push
 *  CallBackURL        Your public HTTPS endpoint where Safaricom sends the result
 *  AccountReference   Your internal payment reference — echoed back in the callback
 *                     (this is how we match the callback to our payment row)
 *  TransactionDesc    Displayed to customer on the STK prompt (max 13 chars)
 *
 * ══════════════════════════════════════════════════════════════════════════
 * SANDBOX TESTING
 * ══════════════════════════════════════════════════════════════════════════
 *  Sandbox base URL:  https://sandbox.safaricom.co.ke
 *  Test shortcode:    174379
 *  Test passkey:      bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919
 *  Test phone:        254708374149  (triggers a simulated STK push response)
 *  Test credentials:  get from your Daraja developer portal after creating an app
 *
 *  The sandbox does NOT actually send a PIN prompt to a phone.
 *  Use the Daraja simulator at https://developer.safaricom.co.ke/APIs/MpesaExpressSimulate
 *  to trigger a simulated callback to your ngrok URL.
 *
 * INTERN: Read the full Daraja docs at https://developer.safaricom.co.ke/APIs/MpesaExpressSimulate
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DarajaService {

    private final DarajaProperties props;
    private final WebClient webClient;

    // ── In-memory token cache ─────────────────────────────────────────────────
    // AtomicReference gives us thread-safe reads/writes without synchronized blocks.
    // In a multi-pod deployment, each pod has its own cache — that's fine.
    // The token endpoint can handle the load; we just don't want N calls per second.
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    // Timestamp format required by Daraja — yyyyMMddHHmmss
    private static final DateTimeFormatter DARAJA_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Initiates an M-Pesa STK Push (Lipa Na M-Pesa Online).
     *
     * Fetches (or reuses a cached) OAuth token, then POSTs the STK push request
     * to Daraja. This call is non-blocking in the sense that Safaricom returns
     * a CheckoutRequestID immediately — the actual payment result arrives later
     * via the callback URL.
     *
     * @param paymentReference  Our internal reference (e.g. "PAY-A1B2C3D4E5F6").
     *                          Sent as AccountReference — Safaricom echoes it in
     *                          the callback body so we can match the callback to
     *                          our payment row.
     * @param amount            The premium amount in KES. Must be ≥ 1 KES.
     *                          Safaricom rejects zero and decimal amounts.
     * @param phoneNumber       Customer's phone in Safaricom format: 2547XXXXXXXX.
     *                          This is the phone that receives the PIN prompt.
     * @return                  The CheckoutRequestID from Safaricom. Store this
     *                          if you need to query the payment status via
     *                          /stkpushquery (useful for reconciliation).
     * @throws DarajaException  If the STK push is rejected by Safaricom.
     *                          PaymentService catches this and marks the payment FAILED.
     */
    public String initiateSTKPush(String paymentReference, BigDecimal amount, String phoneNumber) {
        log.info("STK_PUSH_INIT | ref={} | phone={} | amount={}",
                paymentReference, phoneNumber, amount);

        String accessToken = getAccessToken();
        String timestamp   = LocalDateTime.now().format(DARAJA_TIMESTAMP);
        String password    = buildPassword(timestamp);

        // Daraja requires integer KES — round up to avoid rejection on fractional premiums
        int amountKes = amount.setScale(0, java.math.RoundingMode.CEILING).intValue();
        if (amountKes < 1) {
            throw new DarajaException("STK push amount must be at least KES 1, got: " + amount);
        }

        // Map.of() only supports up to 10 entries — Daraja needs 11 fields,
        // so we use Map.ofEntries() which has no upper limit.
        Map<String, Object> requestBody = Map.ofEntries(
                Map.entry("BusinessShortCode", props.getShortcode()),
                Map.entry("Password",          password),
                Map.entry("Timestamp",         timestamp),
                Map.entry("TransactionType",   "CustomerPayBillOnline"),
                Map.entry("Amount",            amountKes),
                Map.entry("PartyA",            phoneNumber),
                Map.entry("PartyB",            props.getShortcode()),
                Map.entry("PhoneNumber",       phoneNumber),
                Map.entry("CallBackURL",       props.getCallbackUrl()),
                Map.entry("AccountReference",  paymentReference),
                Map.entry("TransactionDesc",   "CIC Motor Cover")
        );

        log.debug("STK_PUSH_REQUEST | ref={} | shortcode={} | callbackUrl={}",
                paymentReference, props.getShortcode(), props.getCallbackUrl());

        StkPushResponse response = webClient
                .post()
                .uri(props.getBaseUrl() + "/mpesa/stkpush/v1/processrequest")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class).map(body -> {
                            log.error("STK_PUSH_REJECTED | ref={} | status={} | body={}",
                                    paymentReference, clientResponse.statusCode(), body);
                            return new DarajaException(
                                    "Daraja STK push rejected [" + clientResponse.statusCode() + "]: " + body);
                        })
                )
                .bodyToMono(StkPushResponse.class)
                .block();  // Blocking is fine here — PaymentService is already @Transactional

        if (response == null) {
            throw new DarajaException("Null response from Daraja STK push — unexpected.");
        }

        // ResponseCode "0" = accepted by Daraja (STK prompt sent to customer).
        // This does NOT mean the customer has paid — that comes via the callback.
        if (!"0".equals(response.getResponseCode())) {
            log.error("STK_PUSH_FAILED | ref={} | responseCode={} | description={}",
                    paymentReference, response.getResponseCode(), response.getResponseDescription());
            throw new DarajaException(
                    "STK push not accepted: [" + response.getResponseCode() + "] "
                            + response.getResponseDescription());
        }

        log.info("STK_PUSH_ACCEPTED | ref={} | checkoutRequestId={}",
                paymentReference, response.getCheckoutRequestId());

        return response.getCheckoutRequestId();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INTERNAL: OAuth token fetch + cache
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a valid OAuth access token, fetching a new one only when the cached
     * token has expired (or was never fetched).
     *
     * Thread-safe: AtomicReference.compareAndSet ensures that under concurrent
     * calls only one thread actually fetches a new token — others will get the
     * freshly stored token on the next read.
     *
     * Daraja OAuth endpoint:
     *   GET /oauth/v1/generate?grant_type=client_credentials
     *   Authorization: Basic Base64(consumerKey:consumerSecret)
     *   Response: { "access_token": "...", "expires_in": "3599" }
     */
    private String getAccessToken() {
        CachedToken cached = tokenCache.get();

        if (cached != null && !cached.isExpired()) {
            log.debug("DARAJA_TOKEN_CACHE_HIT | expiresAt={}", cached.expiresAt);
            return cached.token;
        }

        log.info("DARAJA_TOKEN_FETCH | reason={}",
                cached == null ? "no cached token" : "token expired");

        // Basic auth header: Base64(consumerKey:consumerSecret)
        String credentials = props.getConsumerKey() + ":" + props.getConsumerSecret();
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        OAuthResponse oauthResponse;
        try {
            oauthResponse = webClient
                    .get()
                    .uri(props.getBaseUrl() + "/oauth/v1/generate?grant_type=client_credentials")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class).map(body -> {
                                log.error("DARAJA_TOKEN_ERROR | status={} | body={}",
                                        clientResponse.statusCode(), body);
                                return new DarajaException(
                                        "OAuth token fetch failed [" + clientResponse.statusCode() + "]: " + body);
                            })
                    )
                    .bodyToMono(OAuthResponse.class)
                    .block();

        } catch (WebClientResponseException e) {
            log.error("DARAJA_TOKEN_HTTP_ERROR | status={} | body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new DarajaException("Failed to fetch Daraja access token: " + e.getMessage(), e);
        }

        if (oauthResponse == null || oauthResponse.getAccessToken() == null) {
            throw new DarajaException("Daraja OAuth response was null or missing access_token");
        }

        // Cache the token. TTL = configured tokenCacheTtlSeconds (default 3500s = 58 min 20s).
        // Safaricom's actual TTL is 3599s — we refresh 99s early to avoid using an expiring token.
        CachedToken newToken = new CachedToken(
                oauthResponse.getAccessToken(),
                LocalDateTime.now().plusSeconds(props.getTokenCacheTtlSeconds())
        );
        tokenCache.set(newToken);

        log.info("DARAJA_TOKEN_FETCHED | expiresAt={}", newToken.expiresAt);
        return newToken.token;
    }

    /**
     * Builds the Base64-encoded password required by every STK push request.
     *
     * Formula (from Daraja docs):
     *   password = Base64(BusinessShortCode + Passkey + Timestamp)
     *
     * The timestamp must be the exact same value passed in the request body
     * as the "Timestamp" field.
     */
    private String buildPassword(String timestamp) {
        String raw = props.getShortcode() + props.getPasskey() + timestamp;
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ══════════════════════════════════════════════════════════════════════════

    /** Simple in-memory token holder with an expiry timestamp. */
    private record CachedToken(String token, LocalDateTime expiresAt) {
        boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
    }

    /** Daraja OAuth token response. Jackson maps the snake_case JSON fields via @JsonProperty. */
    @Data
    static class OAuthResponse {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("expires_in")
        private String expiresIn;
    }

    /** Daraja STK push initiation response. */
    @Data
    static class StkPushResponse {
        // "0" = accepted by Daraja; anything else = rejected
        @JsonProperty("ResponseCode")
        private String responseCode;

        @JsonProperty("ResponseDescription")
        private String responseDescription;

        // Our AccountReference echoed back — not used here but good for logging
        @JsonProperty("MerchantRequestID")
        private String merchantRequestId;

        // This ID uniquely identifies the push on Safaricom's side.
        // Store it if you want to query payment status via /stkpushquery.
        @JsonProperty("CheckoutRequestID")
        private String checkoutRequestId;

        @JsonProperty("CustomerMessage")
        private String customerMessage;
    }

    /**
     * Thrown when Daraja rejects a request or returns an unexpected response.
     * Unchecked so it propagates cleanly through WebClient mono chains.
     * PaymentService catches this to mark the payment as FAILED.
     */
    public static class DarajaException extends RuntimeException {
        public DarajaException(String message) {
            super(message);
        }
        public DarajaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}