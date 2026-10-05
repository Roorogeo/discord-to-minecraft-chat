package com.roorogeo.discordwebhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Posts messages to a Discord webhook on a single background thread, so the server thread never
 * blocks on the network and messages arrive in the order they were sent.
 */
public class WebhookClient {
	private static final int MAX_CONTENT_LENGTH = 2000;
	private static final int MAX_EMBED_DESCRIPTION_LENGTH = 4096;
	private static final int MAX_USERNAME_LENGTH = 80;
	private static final int MAX_ATTEMPTS = 3;

	private final URI uri;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Discord Webhook");
		thread.setDaemon(true);
		return thread;
	});

	public WebhookClient(String url) {
		this.uri = URI.create(url);
	}

	/** Sends a plain message, shown as if posted by {@code username}. */
	public void sendMessage(String username, String avatarUrl, String content) {
		JsonObject payload = basePayload(username, avatarUrl);
		payload.addProperty("content", truncate(content, MAX_CONTENT_LENGTH));
		send(payload);
	}

	/** Sends an embed with a colored side bar. */
	public void sendEmbed(String username, String avatarUrl, String description, int color) {
		JsonObject embed = new JsonObject();
		embed.addProperty("description", truncate(description, MAX_EMBED_DESCRIPTION_LENGTH));
		embed.addProperty("color", color);

		JsonArray embeds = new JsonArray();
		embeds.add(embed);

		JsonObject payload = basePayload(username, avatarUrl);
		payload.add("embeds", embeds);
		send(payload);
	}

	/** Waits briefly for queued messages (such as "server stopped") to go out, then stops the worker thread. */
	public void shutdown() {
		executor.shutdown();

		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private static JsonObject basePayload(String username, String avatarUrl) {
		JsonObject payload = new JsonObject();

		if (username != null && !username.isBlank()) {
			payload.addProperty("username", truncate(username, MAX_USERNAME_LENGTH));
		}

		if (avatarUrl != null && !avatarUrl.isBlank()) {
			payload.addProperty("avatar_url", avatarUrl);
		}

		// Never let players ping @everyone, @here, users or roles from in-game chat.
		JsonObject allowedMentions = new JsonObject();
		allowedMentions.add("parse", new JsonArray());
		payload.add("allowed_mentions", allowedMentions);

		return payload;
	}

	private void send(JsonObject payload) {
		if (executor.isShutdown()) {
			return;
		}

		String body = payload.toString();
		executor.execute(() -> post(body));
	}

	private void post(String body) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.header("User-Agent", "DiscordWebhookChat (Fabric mod)")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
				int status = response.statusCode();

				if (status >= 200 && status < 300) {
					return;
				}

				if (status == 429) {
					// Rate limited: Discord tells us how long to wait, in seconds.
					Thread.sleep(retryAfterMillis(response.body()));
					continue;
				}

				DiscordWebhookMod.LOGGER.warn("Discord webhook returned HTTP {}: {}", status, response.body());
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				DiscordWebhookMod.LOGGER.warn("Failed to send Discord webhook message (attempt {}/{})", attempt, MAX_ATTEMPTS, e);
			}
		}
	}

	private static long retryAfterMillis(String body) {
		try {
			double seconds = JsonParser.parseString(body).getAsJsonObject().get("retry_after").getAsDouble();
			return Math.max(250, (long) (seconds * 1000));
		} catch (RuntimeException e) {
			return 1000;
		}
	}

	private static String truncate(String text, int maxLength) {
		return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
	}
}
