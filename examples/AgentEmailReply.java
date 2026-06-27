// An AI email agent: inbound email → Claude drafts a reply → MailKite sends it, threaded.
//
// Flow: MailKite POSTs the inbound `email.received` event → verify it → Claude composes a concise
// reply → send it back with `inReplyTo` so it threads to the sender.
//
// Run:  MAILKITE_API_KEY=mk_live_… MAILKITE_WEBHOOK_SECRET=whsec_… ANTHROPIC_API_KEY=sk-ant-… \
//       java -cp 'mailkite.jar:anthropic-java.jar' AgentEmailReply.java
// Deps: the MailKite jar + the official Anthropic Java SDK (com.anthropic:anthropic-java).

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mailkite.Json;
import dev.mailkite.MailKite;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class AgentEmailReply {
  static final MailKite mk = new MailKite(System.getenv("MAILKITE_API_KEY"));
  static final AnthropicClient claude = AnthropicOkHttpClient.fromEnv();  // reads ANTHROPIC_API_KEY
  static final String SECRET = System.getenv("MAILKITE_WEBHOOK_SECRET");

  static final String SYSTEM =
      "You are the support agent for Acme. Read the customer's email and write a short, friendly "
          + "reply that directly answers them. Plain text. If you can't help, say a human will follow up.";

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(3000), 0);
    server.createContext("/hooks/mailkite", AgentEmailReply::hook);
    server.start();
    System.out.println("agent listening on http://localhost:3000/hooks/mailkite");
  }

  static void hook(HttpExchange ex) throws IOException {
    String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);  // verify the RAW body
    String sig = ex.getRequestHeaders().getFirst("x-mailkite-signature");
    if (!mk.verifyWebhook(sig, raw, SECRET)) {
      respond(ex, 401, "bad signature");
      return;
    }

    Object parsed = Json.parse(raw);
    if (!(parsed instanceof Map)) {
      respond(ex, 200, mk.replyOk());
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> event = (Map<String, Object>) parsed;
    if (!"email.received".equals(event.get("type"))) {
      respond(ex, 200, mk.replyOk());
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> m =
        event.get("message") instanceof Map ? (Map<String, Object>) event.get("message") : event;

    String from = String.valueOf(m.get("from"));
    String to = String.valueOf(m.get("to"));
    String subject = String.valueOf(m.get("subject"));
    String body = m.get("text") != null ? String.valueOf(m.get("text")) : String.valueOf(m.get("html"));

    // 1. Claude drafts the reply.
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Model.CLAUDE_OPUS_4_8)  // or CLAUDE_SONNET_4_6 / CLAUDE_HAIKU_4_5 for lower cost
        .maxTokens(1024L)
        .system(SYSTEM)
        .addUserMessage("From: " + from + "\nSubject: " + subject + "\n\n" + body)
        .build();

    Message response = claude.messages().create(params);
    String reply = response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(textBlock -> textBlock.text())
        .reduce("", String::concat);
    if (reply.isBlank()) {
      reply = "Thanks — a human will follow up.";
    }

    // 2. Send it back, threaded to the original.
    mk.send(Map.of(
        "from", to,    // reply from the address that received the mail
        "to", from,
        "subject", subject.startsWith("Re:") ? subject : "Re: " + subject,
        "text", reply,
        "inReplyTo", m.get("messageId")
    ));
    System.out.println("🤖 replied to " + from);

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
