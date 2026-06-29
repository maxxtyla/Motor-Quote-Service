package com.cic.motor_quote_service.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe binding for all Daraja (Safaricom M-Pesa) configuration.
 *
 * Bound from the `mpesa.*` block in application.yaml.
 * @Validated means Spring will fail fast at startup if any @NotBlank
 * field is missing — you get a clear error instead of a NullPointerException
 * at the moment the first payment is attempted.
 *
 * HOW TO GET THESE VALUES:
 *   1. Register at https://developer.safaricom.co.ke
 *   2. Create an app → copy Consumer Key + Consumer Secret
 *   3. Go to "My Apps" → your app → "Go Live" for production credentials
 *   4. Shortcode   = your Paybill or Till Number
 *   5. Passkey     = provided by Safaricom on your app's portal page
 *   6. CallbackURL = must be HTTPS and publicly reachable by Safaricom's servers
 *                    For local dev: use ngrok (`ngrok http 8080`) to expose localhost
 *
 * SANDBOX vs PRODUCTION:
 *   Sandbox base URL:    https://sandbox.safaricom.co.ke
 *   Production base URL: https://api.safaricom.co.ke
 *   Set `mpesa.base-url` accordingly per environment.
 *
 * INTERN: Never commit real credentials. Set these via environment variables
 * injected into your Docker / Kubernetes deployment:
 *   MPESA_CONSUMER_KEY, MPESA_CONSUMER_SECRET, MPESA_PASSKEY, MPESA_SHORTCODE
 */
@Data
@Validated
@ConfigurationProperties(prefix = "mpesa")
public class DarajaProperties {

    /**
     * Safaricom Daraja base URL.
     * Sandbox: https://sandbox.safaricom.co.ke
     * Production: https://api.safaricom.co.ke
     */
    @NotBlank(message = "mpesa.base-url is required")
    private String baseUrl;

    /**
     * OAuth Consumer Key — from your Daraja app dashboard.
     */
    @NotBlank(message = "mpesa.consumer-key is required")
    private String consumerKey;

    /**
     * OAuth Consumer Secret — from your Daraja app dashboard.
     */
    @NotBlank(message = "mpesa.consumer-secret is required")
    private String consumerSecret;

    /**
     * Your Paybill or Till Number (e.g. "174379" for the sandbox test shortcode).
     */
    @NotBlank(message = "mpesa.shortcode is required")
    private String shortcode;

    /**
     * Lipa Na M-Pesa Online Passkey — provided on your Daraja app dashboard.
     * Used to generate the Base64 password for each STK push request.
     */
    @NotBlank(message = "mpesa.passkey is required")
    private String passkey;

    /**
     * Publicly reachable HTTPS URL where Safaricom will POST the callback.
     * Maps to POST /payments/mpesa/callback in MpesaCallbackController.
     *
     * Local dev: use `ngrok http 8080` and set this to your ngrok URL, e.g.:
     *   https://abc123.ngrok-free.app/payments/mpesa/callback
     */
    @NotBlank(message = "mpesa.callback-url is required")
    private String callbackUrl;

    /**
     * OAuth token TTL in seconds — Safaricom tokens are valid for 3599s (~1 hour).
     * We cache the token and re-fetch slightly before expiry.
     * Default: 3500 (59 minutes — 59s buffer before the real 3599s expiry).
     */
    @NotNull
    private Integer tokenCacheTtlSeconds = 3500;
}