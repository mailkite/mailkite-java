// Server-side login + register — your own account, or your users' accounts via OAuth 2.1 + PKCE.
//
//   A) Your OWN account: call signup (register) or login with email + password, keep the token.
//   B) YOUR USERS' accounts (multi-tenant): the OAuth 2.1 + PKCE flow — send the user to MailKite's
//      hosted page where they LOG IN OR REGISTER, then exchange the returned `code` for a token that
//      *is* that user. Register-or-login is handled on the hosted page.
//
// Run:  MAILKITE_BASE_URL=https://api.mailkite.dev java -cp mailkite.jar ServerLogin.java
//       then open http://localhost:3000/login
// Uses only the JDK: java.net.http.HttpClient + com.sun.net.httpserver.HttpServer — no extra deps.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mailkite.Json;
import dev.mailkite.MailKite;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerLogin {
  static final String ISSUER =
      System.getenv().getOrDefault("MAILKITE_BASE_URL", "https://api.mailkite.dev");
  static final String REDIRECT_URI = "http://localhost:3000/callback";

  static final HttpClient http = HttpClient.newHttpClient();
  static final SecureRandom rng = new SecureRandom();
  // demo store: state → {verifier, clientId}. Use a real session store in prod.
  static final Map<String, String[]> sessions = new ConcurrentHashMap<>();

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(3000), 0);
    server.createContext("/login", ServerLogin::login);
    server.createContext("/callback", ServerLogin::callback);
    server.start();
    System.out.println("open http://localhost:3000/login");
  }

  // ── A) Server acting as your OWN single account (no redirect) ──────────────────────────────────
  @SuppressWarnings("unchecked")
  static void ownAccount() throws IOException, InterruptedException {
    Map<String, Object> creds = Map.of(
        "email", "you@example.com", "password", System.getenv("MK_PASSWORD"));
    HttpResponse<String> r = postJson(ISSUER + "/api/auth/signup", creds);
    if (r.statusCode() == 409) {  // already registered → log in instead
      r = postJson(ISSUER + "/api/auth/login", creds);
    }
    String token = (String) ((Map<String, Object>) Json.parse(r.body())).get("token");
    MailKite mk = new MailKite(token);  // the session token works like an API key
    System.out.println("logged in as own account; domains: " + mk.listDomains());
  }

  // ── B) OAuth login/register for YOUR USERS ─────────────────────────────────────────────────────
  @SuppressWarnings("unchecked")
  static void login(HttpExchange ex) throws IOException {
    try {
      Map<String, Object> reg = (Map<String, Object>) Json.parse(postJson(ISSUER + "/oauth/register", Map.of(
          "client_name", "My App",
          "redirect_uris", List.of(REDIRECT_URI),
          "grant_types", List.of("authorization_code", "refresh_token"),
          "response_types", List.of("code"))).body());
      String clientId = (String) reg.get("client_id");

      String verifier = b64url(randomBytes(32));
      String challenge = b64url(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
      String state = b64url(randomBytes(16));
      sessions.put(state, new String[] {verifier, clientId});

      String params = form(Map.of(
          "response_type", "code",
          "client_id", clientId,
          "redirect_uri", REDIRECT_URI,
          "scope", "mcp",
          "state", state,
          "code_challenge", challenge,
          "code_challenge_method", "S256"));
      ex.getResponseHeaders().set("Location", ISSUER + "/oauth/authorize?" + params);
      ex.sendResponseHeaders(302, -1);
      ex.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respond(ex, 500, "interrupted");
    }
  }

  @SuppressWarnings("unchecked")
  static void callback(HttpExchange ex) throws IOException {
    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
    String[] sess = sessions.remove(q.getOrDefault("state", ""));
    if (sess == null) {
      respond(ex, 400, "unknown state");
      return;
    }
    try {
      Map<String, Object> tok = (Map<String, Object>) Json.parse(postForm(ISSUER + "/oauth/token", Map.of(
          "grant_type", "authorization_code",
          "code", q.get("code"),
          "redirect_uri", REDIRECT_URI,
          "client_id", sess[1],          // client_id
          "code_verifier", sess[0]))     // verifier
          .body());
      // now act as that user (store refresh_token to renew later)
      MailKite mk = new MailKite((String) tok.get("access_token"));
      respond(ex, 200, "{\"ok\":true,\"message\":\"Logged in as the MailKite user.\",\"domains\":"
          + Json.stringify(mk.listDomains()) + "}");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      respond(ex, 500, "interrupted");
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────
  static HttpResponse<String> postJson(String url, Object body) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(Json.stringify(body)))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  static HttpResponse<String> postForm(String url, Map<String, String> fields)
      throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(BodyPublishers.ofString(form(fields)))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  static String b64url(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    rng.nextBytes(b);
    return b;
  }

  static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);  // SHA-256 is always available
    }
  }

  static String form(Map<String, String> fields) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : fields.entrySet()) {
      if (sb.length() > 0) sb.append('&');
      sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
    }
    return sb.toString();
  }

  static Map<String, String> query(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return out;
    for (String pair : raw.split("&")) {
      int i = pair.indexOf('=');
      if (i < 0) continue;
      out.put(dec(pair.substring(0, i)), dec(pair.substring(i + 1)));
    }
    return out;
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static String dec(String s) {
    return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
