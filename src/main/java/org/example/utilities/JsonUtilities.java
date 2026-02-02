package org.example.utilities;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class JsonUtilities {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(DEFAULT_TIMEOUT)
            .build();

    private JsonUtilities() {
        // Utility class: prevent instantiation
    }

    public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is null/blank");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DEFAULT_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching URL: " + url, e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " while fetching URL: " + url);
        }

        String jsonText = response.body();
        if (jsonText == null) {
            jsonText = "";
        }
        return new JSONObject(jsonText);
    }
}
