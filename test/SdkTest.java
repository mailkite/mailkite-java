package dev.mailkite.test;

import com.sun.net.httpserver.HttpServer;
import dev.mailkite.Json;
import dev.mailkite.MailKite;
import dev.mailkite.MailKiteException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Unit tests for the MailKite Java SDK — a dependency-free runner (no JUnit on
 * the classpath here). Covers request(), every endpoint method, and
 * verifyWebhook against an in-process {@link HttpServer}.
 *
 * <p>Compile + run (see run-tests.sh):
 * <pre>
 *   javac -d .test-build src/main/java/dev/mailkite/*.java test/SdkTest.java
 *   java -cp .test-build dev.mailkite.test.SdkTest
 * </pre>
 */
public class SdkTest {
  static int failures = 0;
  static int checks = 0;

  // programmable response + recorded request
  static int respStatus = 200;
  static String respBody = "{\"ok\":true}";
  static String lastMethod, lastPath, lastAuth, lastContentType, lastBody;

  static final String KEY = "mk_live_test";
  static final String SECRET = "whsec_mailkite_test";
  static final String PAYLOAD = "{\"type\":\"email.received\",\"id\":\"evt_123\",\"message\":\"It works.\"}";
  static final String V1 = "3d790f831e170ddba4d001f27532bf2c1fc68ebed52eef72fe453dfa1196b03c";
  static final String HEADER = "t=1750000000000,v1=" + V1;

  public static void main(String[] argv) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", (exchange) -> {
      byte[] in = exchange.getRequestBody().readAllBytes();
      lastMethod = exchange.getRequestMethod();
      lastPath = exchange.getRequestURI().getPath();
      lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
      lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
      lastBody = new String(in, StandardCharsets.UTF_8);
      byte[] out = respBody == null ? new byte[0] : respBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      // 204 must not send a body length; use -1 for no content.
      if (out.length == 0) {
        exchange.sendResponseHeaders(respStatus, -1);
      } else {
        exchange.sendResponseHeaders(respStatus, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(out);
        }
      }
      exchange.close();
    });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    MailKite mk = new MailKite(KEY, base);

    // ---- constructor: base URL trims trailing slashes -----------------------
    respond(200, "[]");
    new MailKite(KEY, base + "///").listDomains();
    check("base URL trims trailing slashes", "/api/domains".equals(lastPath));

    // ---- request() ----------------------------------------------------------
    respond(200, "{\"id\":\"x\",\"status\":\"queued\"}");
    Object res = mk.request("POST", "/v1/send", map("a", 1));
    check("request sends Bearer auth", KEY.equals(strip(lastAuth)));
    check("request sets JSON content-type", lastContentType != null && lastContentType.contains("application/json"));
    check("request serializes the JSON body", "{\"a\":1}".equals(compact(lastBody)));
    check("request parses the result", res instanceof Map && "x".equals(((Map<?, ?>) res).get("id")));

    respond(200, "[]");
    mk.request("GET", "/api/domains", null);
    check("request with no body sends no payload", lastBody.isEmpty());
    check("request with no body sends no content-type", lastContentType == null);

    respond(204, null);
    check("request returns null for an empty body", mk.request("DELETE", "/api/x", null) == null);

    respond(404, "{\"error\":\"not found\"}");
    try {
      mk.request("GET", "/api/messages/nope", null);
      check("request throws on error", false);
    } catch (MailKiteException e) {
      check("error carries status", e.status == 404);
      check("error carries message", "not found".equals(e.getMessage()));
    }

    respond(500, "{\"nope\":true}");
    try {
      mk.request("GET", "/x", null);
      check("request throws on 500", false);
    } catch (MailKiteException e) {
      check("error status 500", e.status == 500);
    }

    // ---- endpoint methods ---------------------------------------------------
    endpoint("send", () -> mk.send(map("from", "a", "to", "b", "subject", "s", "text", "t")),
        "POST", "/v1/send", "{\"from\":\"a\",\"to\":\"b\",\"subject\":\"s\",\"text\":\"t\"}");
    endpoint("listDomains", mk::listDomains, "GET", "/api/domains", null);
    endpoint("createDomain", () -> mk.createDomain(map("domain", "x.dev")), "POST", "/api/domains", "{\"domain\":\"x.dev\"}");
    endpoint("getDomain", () -> mk.getDomain("dom_1"), "GET", "/api/domains/dom_1", null);
    endpoint("deleteDomain", () -> mk.deleteDomain("dom_1"), "DELETE", "/api/domains/dom_1", null);
    endpoint("verifyDomain", () -> mk.verifyDomain("dom_1"), "POST", "/api/domains/dom_1/verify", null);
    endpoint("setWebhook", () -> mk.setWebhook("dom_1", map("url", "https://h.dev")), "PUT", "/api/domains/dom_1/webhook", "{\"url\":\"https://h.dev\"}");
    endpoint("deleteWebhook", () -> mk.deleteWebhook("dom_1"), "DELETE", "/api/domains/dom_1/webhook", null);
    endpoint("testWebhook", () -> mk.testWebhook("dom_1"), "POST", "/api/domains/dom_1/webhook/test", null);
    endpoint("listRoutes", mk::listRoutes, "GET", "/api/routes", null);
    endpoint("createRoute", () -> mk.createRoute(map("match", "*@x", "action", "webhook", "destination", "u")), "POST", "/api/routes", "{\"match\":\"*@x\",\"action\":\"webhook\",\"destination\":\"u\"}");
    endpoint("listMessages", mk::listMessages, "GET", "/api/messages", null);
    endpoint("getMessage", () -> mk.getMessage("msg_1"), "GET", "/api/messages/msg_1", null);
    endpoint("retryDelivery", () -> mk.retryDelivery("dlv_1"), "POST", "/api/deliveries/dlv_1/retry", null);

    // ---- verifyWebhook ------------------------------------------------------
    check("verifyWebhook valid (tolerance 0)", mk.verifyWebhook(HEADER, PAYLOAD, SECRET, 0));
    check("verifyWebhook tampered body", !mk.verifyWebhook(HEADER, PAYLOAD + " ", SECRET, 0));
    check("verifyWebhook wrong secret", !mk.verifyWebhook(HEADER, PAYLOAD, "whsec_wrong", 0));
    check("verifyWebhook empty header", !mk.verifyWebhook("", PAYLOAD, SECRET, 0));
    check("verifyWebhook garbage header", !mk.verifyWebhook("garbage", PAYLOAD, SECRET, 0));
    check("verifyWebhook missing v1", !mk.verifyWebhook("t=1750000000000", PAYLOAD, SECRET, 0));
    check("verifyWebhook missing t", !mk.verifyWebhook("v1=" + V1, PAYLOAD, SECRET, 0));
    check("verifyWebhook non-numeric t", !mk.verifyWebhook("t=nan,v1=" + V1, PAYLOAD, SECRET, 0));
    // Default 5-minute window: fixed vector is stale, a freshly signed one passes.
    check("verifyWebhook default window rejects stale", !mk.verifyWebhook(HEADER, PAYLOAD, SECRET));
    check("verifyWebhook default window accepts fresh", mk.verifyWebhook(freshHeader(SECRET, PAYLOAD), PAYLOAD, SECRET));

    server.stop(0);
    System.out.println(failures == 0
        ? ("\nALL " + checks + " CHECKS PASS")
        : ("\n" + failures + "/" + checks + " FAILED"));
    System.exit(failures == 0 ? 0 : 1);
  }

  interface Call {
    Object run();
  }

  static void endpoint(String name, Call call, String method, String path, String expectBody) {
    respond(200, "{\"ok\":true}");
    call.run();
    boolean ok = method.equals(lastMethod) && path.equals(lastPath);
    if (expectBody == null) ok = ok && lastBody.isEmpty();
    else ok = ok && expectBody.equals(compact(lastBody));
    check(name + " -> " + method + " " + path, ok);
  }

  static void respond(int status, String body) {
    respStatus = status;
    respBody = body;
  }

  static void check(String label, boolean cond) {
    checks++;
    if (!cond) {
      failures++;
      System.out.println("FAIL: " + label);
    } else {
      System.out.println("ok  : " + label);
    }
  }

  static String strip(String auth) {
    return auth == null ? null : auth.replaceFirst("^Bearer ", "");
  }

  // Re-serialize parsed JSON to a canonical compact string for comparison.
  static String compact(String json) {
    if (json == null || json.isEmpty()) return "";
    return Json.stringify(Json.parse(json));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  static String freshHeader(String secret, String body) throws Exception {
    long t = System.currentTimeMillis();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] raw = mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    for (byte b : raw) sb.append(String.format("%02x", b));
    return "t=" + t + ",v1=" + sb;
  }
}
