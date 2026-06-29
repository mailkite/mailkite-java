// Receive inbound email as a webhook — and VERIFY the HMAC signature before trusting it.
//
// MailKite POSTs a signed `email.received` event to your URL. Always verify the
// `x-mailkite-signature` header against your webhook secret so inbound mail can't be forged.
//
// Run:  MAILKITE_WEBHOOK_SECRET=whsec_… java -cp mailkite.jar ReceiveWebhook.java
// Uses only the JDK's com.sun.net.httpserver.HttpServer — no extra dependencies.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mailkite.Json;
import dev.mailkite.MailKite;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ReceiveWebhook {
  // MAILKITE_API_KEY is unused for verify-only; verifyWebhook is a local HMAC check.
  static final MailKite mk = new MailKite(System.getenv().getOrDefault("MAILKITE_API_KEY", "unused-for-verify"));
  static final String SECRET = System.getenv("MAILKITE_WEBHOOK_SECRET");

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(3000), 0);
    server.createContext("/hooks/mailkite", ReceiveWebhook::hook);
    server.start();
    System.out.println("listening on http://localhost:3000/hooks/mailkite");
  }

  static void hook(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      respond(ex, 405, "method not allowed");
      return;
    }

    // The RAW body — re-serialized JSON breaks the HMAC.
    String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String sig = ex.getRequestHeaders().getFirst("x-mailkite-signature");

    // Note: the Java SDK's verifyWebhook takes positional (signature, payload, secret) and
    // returns a boolean — not a Map.
    if (!mk.verifyWebhook(sig, raw, SECRET)) {
      respond(ex, 401, "bad signature");
      return;
    }

    Object parsed = Json.parse(raw);
    if (parsed instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> event = (Map<String, Object>) parsed;
      if ("email.received".equals(event.get("type"))) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m =
            event.get("message") instanceof Map ? (Map<String, Object>) event.get("message") : event;
        System.out.printf("📬 %s → %s: %s%n", m.get("from"), m.get("to"), m.get("subject"));
        // …store it, notify a channel, kick off a workflow…
      }
    }

    // 200 acknowledges; return a control body (mk.replySpam()/replyDrop()/replyBlockSender())
    // to mark spam / drop / block instead.
    respond(ex, 200, mk.replyOk());
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
