package dev.mailkite;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * MailKite SDK for Java (11+).
 *
 * <p>Shape shared by every MailKite SDK: one low-level {@link #request} plus one
 * thin method per API endpoint. Request bodies and responses use plain
 * {@code Map}/{@code List} values via the bundled {@link Json} helper, so there
 * are no external dependencies.
 *
 * <pre>{@code
 * MailKite mk = new MailKite(System.getenv("MAILKITE_API_KEY"));
 * Map<String, Object> msg = new HashMap<>();
 * msg.put("from", "hello@app.mailkite.dev");
 * msg.put("to", "ada@example.com");
 * msg.put("subject", "Hi");
 * msg.put("text", "It works.");
 * Object res = mk.send(msg);
 * }</pre>
 */
public class MailKite {
  public static final String DEFAULT_BASE_URL = "https://api.mailkite.dev";
  /** Reject webhook events older than this (ms) to block replays. Pass 0 to disable. */
  public static final long DEFAULT_TOLERANCE_MS = 5 * 60 * 1000L;

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient http = HttpClient.newHttpClient();

  public MailKite(String apiKey) {
    this(apiKey, DEFAULT_BASE_URL);
  }

  public MailKite(String apiKey, String baseUrl) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
  }

  /** Low-level request. Every method below is a one-liner on top of this. */
  public Object request(String method, String path, Object body) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
      b.header("Authorization", "Bearer " + apiKey);
      if (body != null) {
        b.header("Content-Type", "application/json");
        b.method(method, BodyPublishers.ofString(Json.stringify(body)));
      } else {
        b.method(method, BodyPublishers.noBody());
      }
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      String text = res.body();
      Object data = (text == null || text.isEmpty()) ? null : Json.parse(text);
      int status = res.statusCode();
      if (status < 200 || status >= 300) {
        String message = "HTTP " + status;
        if (data instanceof Map && ((Map<?, ?>) data).get("error") instanceof String) {
          message = (String) ((Map<?, ?>) data).get("error");
        }
        throw new MailKiteException(status, message, data);
      }
      return data;
    } catch (java.io.IOException | InterruptedException e) {
      throw new MailKiteException(0, e.getMessage(), null);
    }
  }

  /** Build a {@code ?before=…&limit=…} query string, omitting null params. */
  private static String pageQuery(Long before, Integer limit) {
    StringBuilder qs = new StringBuilder();
    if (before != null) {
      qs.append(qs.length() == 0 ? "?" : "&")
          .append("before=")
          .append(URLEncoder.encode(String.valueOf(before), StandardCharsets.UTF_8));
    }
    if (limit != null) {
      qs.append(qs.length() == 0 ? "?" : "&")
          .append("limit=")
          .append(URLEncoder.encode(String.valueOf(limit), StandardCharsets.UTF_8));
    }
    return qs.toString();
  }

  // --- Sending --------------------------------------------------------------

  /**
   * Send a message. {@code message} is a {@code Map} of fields: {@code from} and {@code to}
   * are required; {@code subject}, {@code text}, {@code html}, {@code cc}, {@code bcc} and
   * {@code replyTo} are optional. To render from a template pass {@code templateId} (String)
   * and optional {@code templateData} ({@code Map<String, Object>}); {@code subject} is then
   * optional and may be supplied by the template.
   */
  public Object send(Object message) {
    return request("POST", "/v1/send", message);
  }

  // ext -> MIME for raw-binary uploads (path/bytes). Default application/octet-stream.
  private static final Map<String, String> EXT_MIME = buildExtMime();

  private static Map<String, String> buildExtMime() {
    Map<String, String> m = new java.util.HashMap<>();
    m.put("pdf", "application/pdf");
    m.put("png", "image/png");
    m.put("jpg", "image/jpeg");
    m.put("jpeg", "image/jpeg");
    m.put("gif", "image/gif");
    m.put("webp", "image/webp");
    m.put("svg", "image/svg+xml");
    m.put("csv", "text/csv");
    m.put("txt", "text/plain");
    m.put("html", "text/html");
    m.put("json", "application/json");
    m.put("zip", "application/zip");
    m.put("doc", "application/msword");
    m.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    m.put("xls", "application/vnd.ms-excel");
    m.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    m.put("ics", "text/calendar");
    m.put("ical", "text/calendar");
    return m;
  }

  private static String guessContentType(String filename) {
    if (filename != null) {
      int dot = filename.lastIndexOf('.');
      if (dot >= 0 && dot < filename.length() - 1) {
        String ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String mime = EXT_MIME.get(ext);
        if (mime != null) return mime;
      }
    }
    return "application/octet-stream";
  }

  /**
   * Upload a file and get back a secure, time-limited URL to reference as a send()
   * attachment ({@code { filename, url }}) or link inline — instead of base64-inlining
   * large files on every send.
   *
   * <p>{@code file} is a {@code Map} that supplies the source ONE of four ways (checked
   * in this priority order):
   * <ol>
   *   <li>{@code url} (String) — MailKite fetches &amp; re-hosts it (JSON POST);
   *   <li>{@code bytes} ({@code byte[]}) — raw binary upload;
   *   <li>{@code path} (String) — read the local file off disk, then raw binary upload;
   *   <li>{@code content} (String, base64) — JSON POST with the inline base64 body.
   * </ol>
   * Optional {@code filename}, {@code contentType} and {@code retentionDays} apply to all
   * modes. {@code path}/{@code bytes} are client-side only and never sent in a JSON body.
   */
  @SuppressWarnings("unchecked")
  public Object uploadAttachment(Object file) {
    Map<String, Object> f = (file instanceof Map) ? (Map<String, Object>) file : null;
    Object url = f == null ? null : f.get("url");
    Object bytes = f == null ? null : f.get("bytes");
    Object path = f == null ? null : f.get("path");
    Object content = f == null ? null : f.get("content");
    Object filename = f == null ? null : f.get("filename");
    Object contentType = f == null ? null : f.get("contentType");
    Object retentionDays = f == null ? null : f.get("retentionDays");

    // 1. url -> JSON POST {url, filename?, contentType?, retentionDays?}
    if (url instanceof String) {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("url", url);
      if (filename != null) body.put("filename", filename);
      if (contentType != null) body.put("contentType", contentType);
      if (retentionDays != null) body.put("retentionDays", retentionDays);
      return request("POST", "/v1/attachments", body);
    }

    // 2. bytes -> raw binary upload
    if (bytes instanceof byte[]) {
      return uploadBinary(
          (byte[]) bytes, asString(filename), asString(contentType), retentionDays);
    }

    // 3. path -> read off disk, then raw binary upload
    if (path instanceof String) {
      byte[] data;
      try {
        data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get((String) path));
      } catch (java.io.IOException e) {
        throw new MailKiteException(0, "uploadAttachment: cannot read " + path + ": " + e.getMessage(), null);
      }
      String name = filename != null ? asString(filename)
          : java.nio.file.Paths.get((String) path).getFileName().toString();
      String ct = contentType != null ? asString(contentType) : guessContentType(name);
      return uploadBinary(data, name, ct, retentionDays);
    }

    // 4. content (base64) -> JSON POST {content, filename, contentType?, retentionDays?}
    if (content instanceof String) {
      Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("content", content);
      if (filename != null) body.put("filename", filename);
      if (contentType != null) body.put("contentType", contentType);
      if (retentionDays != null) body.put("retentionDays", retentionDays);
      return request("POST", "/v1/attachments", body);
    }

    throw new MailKiteException(
        0, "uploadAttachment requires one of: url, bytes, path, or content", null);
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  /**
   * Raw-binary attachment upload: POST the bytes (not JSON, not multipart) to
   * {@code /v1/attachments?filename=&amp;retentionDays=}. Mirrors {@link #request} for
   * auth and response parsing.
   */
  private Object uploadBinary(byte[] data, String filename, String contentType, Object retentionDays) {
    String ct = (contentType != null && !contentType.isEmpty())
        ? contentType
        : guessContentType(filename);
    StringBuilder query = new StringBuilder("/v1/attachments");
    boolean hasName = filename != null && !filename.isEmpty();
    if (hasName) {
      query.append("?filename=").append(URLEncoder.encode(filename, StandardCharsets.UTF_8));
    }
    if (retentionDays != null) {
      query.append(hasName ? "&" : "?").append("retentionDays=").append(retentionDays);
    }
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + query));
      b.header("Authorization", "Bearer " + apiKey);
      b.header("Content-Type", ct);
      b.method("POST", BodyPublishers.ofByteArray(data));
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      String text = res.body();
      Object parsed = (text == null || text.isEmpty()) ? null : Json.parse(text);
      int status = res.statusCode();
      if (status < 200 || status >= 300) {
        String message = "HTTP " + status;
        if (parsed instanceof Map && ((Map<?, ?>) parsed).get("error") instanceof String) {
          message = (String) ((Map<?, ?>) parsed).get("error");
        }
        throw new MailKiteException(status, message, parsed);
      }
      return parsed;
    } catch (java.io.IOException | InterruptedException e) {
      throw new MailKiteException(0, e.getMessage(), null);
    }
  }

  /**
   * Send a message to an AI agent. {@code message} is a {@code Map} of fields: {@code text}
   * is required; {@code subject}, {@code from}, {@code html}, {@code routeId}, {@code address}
   * and {@code model} are optional.
   */
  public Object agent(Object message) {
    return request("POST", "/v1/agent", message);
  }

  /**
   * Send a message into a route. {@code message} is a {@code Map} of fields: {@code from} is
   * required; {@code routeId}, {@code address}, {@code subject}, {@code text} and {@code html}
   * are optional.
   */
  public Object route(Object message) {
    return request("POST", "/v1/route", message);
  }

  // --- Domains --------------------------------------------------------------
  public Object listDomains() {
    return request("GET", "/api/domains", null);
  }

  public Object createDomain(Object body) {
    return request("POST", "/api/domains", body);
  }

  public Object getDomain(String id) {
    return request("GET", "/api/domains/" + id, null);
  }

  public Object deleteDomain(String id) {
    return request("DELETE", "/api/domains/" + id, null);
  }

  public Object verifyDomain(String id) {
    return request("POST", "/api/domains/" + id + "/verify", null);
  }

  public Object setWebhook(String id, Object body) {
    return request("PUT", "/api/domains/" + id + "/webhook", body);
  }

  public Object deleteWebhook(String id) {
    return request("DELETE", "/api/domains/" + id + "/webhook", null);
  }

  public Object testWebhook(String id) {
    return request("POST", "/api/domains/" + id + "/webhook/test", null);
  }

  /** Check whether {@code domain} is available to register (read-only). */
  public Object checkDomainAvailability(String domain) {
    return request(
        "GET",
        "/api/domains/register/check?domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8),
        null);
  }

  /** Register a new domain. */
  public Object registerDomain(Object body) {
    return request("POST", "/api/domains/register", body);
  }

  // --- Docs -----------------------------------------------------------------

  /** Semantic search over the MailKite docs (read-only, public). */
  public Object semanticSearch(String query) {
    return request(
        "GET",
        "/v1/docs/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
        null);
  }

  // --- Routes ---------------------------------------------------------------
  public Object listRoutes() {
    return request("GET", "/api/routes", null);
  }

  public Object createRoute(Object body) {
    return request("POST", "/api/routes", body);
  }

  // --- Messages & deliveries ------------------------------------------------
  public Object listMessages() {
    return listMessages(null, null);
  }

  /** List messages with optional {@code before} cursor and {@code limit} page size. */
  public Object listMessages(Long before, Integer limit) {
    return request("GET", "/api/messages" + pageQuery(before, limit), null);
  }

  public Object getMessage(String id) {
    return request("GET", "/api/messages/" + id, null);
  }

  public Object retryDelivery(String id) {
    return request("POST", "/api/deliveries/" + id + "/retry", null);
  }

  // --- Templates ------------------------------------------------------------
  public Object listTemplates() {
    return request("GET", "/api/templates", null);
  }

  public Object listBaseTemplates() {
    return request("GET", "/api/templates/base", null);
  }

  public Object getTemplate(String id) {
    return request("GET", "/api/templates/" + id, null);
  }

  public Object createTemplate(Object body) {
    return request("POST", "/api/templates", body);
  }

  // --- Lists ----------------------------------------------------------------
  public Object listLists() {
    return request("GET", "/api/lists", null);
  }

  public Object createList(Object body) {
    return request("POST", "/api/lists", body);
  }

  public Object getList(String id) {
    return request("GET", "/api/lists/" + id, null);
  }

  public Object updateList(String id, Object body) {
    return request("PATCH", "/api/lists/" + id, body);
  }

  public Object deleteList(String id) {
    return request("DELETE", "/api/lists/" + id, null);
  }

  public Object listListContacts(String id) {
    return listListContacts(id, null, null);
  }

  /** List contacts in a list with optional {@code before} cursor and {@code limit} page size. */
  public Object listListContacts(String id, Long before, Integer limit) {
    return request("GET", "/api/lists/" + id + "/contacts" + pageQuery(before, limit), null);
  }

  public Object addListContacts(String id, Object body) {
    return request("POST", "/api/lists/" + id + "/contacts", body);
  }

  public Object removeListContact(String id, String contactId) {
    return request("DELETE", "/api/lists/" + id + "/contacts/" + contactId, null);
  }

  // --- Broadcasts -----------------------------------------------------------
  public Object listBroadcasts() {
    return request("GET", "/api/broadcasts", null);
  }

  public Object createBroadcast(Object body) {
    return request("POST", "/api/broadcasts", body);
  }

  public Object getBroadcast(String id) {
    return request("GET", "/api/broadcasts/" + id, null);
  }

  public Object updateBroadcast(String id, Object body) {
    return request("PATCH", "/api/broadcasts/" + id, body);
  }

  public Object deleteBroadcast(String id) {
    return request("DELETE", "/api/broadcasts/" + id, null);
  }

  public Object sendBroadcast(String id, Object body) {
    return request("POST", "/api/broadcasts/" + id + "/send", body);
  }

  // --- Webhooks -------------------------------------------------------------

  /** Verify the {@code x-mailkite-signature} header using the default 5-minute window. */
  public boolean verifyWebhook(String signature, String payload, String secret) {
    return verifyWebhook(signature, payload, secret, DEFAULT_TOLERANCE_MS);
  }

  /**
   * Verify the {@code x-mailkite-signature} header on an inbound webhook delivery.
   * Local HMAC-SHA256 check — no network call. Pass the raw, unparsed body.
   *
   * @param toleranceMs reject events older than this many ms (0 disables the check).
   */
  public boolean verifyWebhook(String signature, String payload, String secret, long toleranceMs) {
    if (signature == null || signature.isEmpty()) return false;

    String t = null, v1 = null;
    for (String seg : signature.split(",")) {
      int i = seg.indexOf('=');
      if (i < 0) continue;
      String k = seg.substring(0, i).trim();
      String v = seg.substring(i + 1).trim();
      if (k.equals("t")) t = v;
      else if (k.equals("v1")) v1 = v;
    }
    if (t == null || v1 == null || v1.isEmpty() || !t.matches("-?\\d+")) return false;

    long ts;
    try {
      ts = Long.parseLong(t);
    } catch (NumberFormatException e) {
      return false;
    }
    // The t in the header is milliseconds since the epoch.
    if (toleranceMs > 0 && Math.abs(System.currentTimeMillis() - ts) > toleranceMs) return false;

    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] raw = mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(raw.length * 2);
      for (byte b : raw) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      // MessageDigest.isEqual is constant-time on modern JDKs.
      return MessageDigest.isEqual(
          sb.toString().getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException e) {
      return false;
    }
  }

  // --- Webhook reply helper -------------------------------------------------

  /**
   * The canonical 200 body to return from your webhook handler so MailKite marks the
   * delivery acknowledged. Local — no network call.
   */
  public String replyOk() {
    return "{\"status\":\"ok\"}";
  }

  /**
   * Control-mode reply telling MailKite to mark the message as spam:
   * the string {@code {"status":"spam"}}. Local — no network call.
   */
  public String replySpam() {
    return "{\"status\":\"spam\"}";
  }

  /**
   * Control-mode reply telling MailKite to drop (discard) the message:
   * the string {@code {"status":"drop"}}. Local — no network call.
   */
  public String replyDrop() {
    return "{\"status\":\"drop\"}";
  }

  /**
   * Control-mode reply telling MailKite to block the sender:
   * the string {@code {"status":"ok","actions":[{"type":"block-sender"}]}}. Local — no network call.
   */
  public String replyBlockSender() {
    return "{\"status\":\"ok\",\"actions\":[{\"type\":\"block-sender\"}]}";
  }

  // --- At-rest encryption ---------------------------------------------------
  // Hybrid envelope matching MailKite's at-rest scheme (see the API's encryption.ts):
  //   1. a fresh AES-256-GCM content key encrypts the plaintext,
  //   2. the content key is wrapped with the customer's RSA-OAEP (SHA-256) public key.
  // Byte-compatible with WebCrypto: AES-GCM doFinal yields ciphertext||tag, which is exactly
  // what WebCrypto emits/expects. RSA-OAEP uses SHA-256 for both the hash AND MGF1 — set
  // explicitly via OAEPParameterSpec so we don't inherit a JDK default of MGF1-SHA-1.

  private static final String ENC_ALG = "RSA-OAEP-256";

  private static OAEPParameterSpec oaepSha256() {
    return new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
  }

  /** Strip PEM armor and base64-decode the DER body. */
  private static byte[] pemToDer(String pem) {
    String body =
        pem.replaceAll("-----BEGIN [^-]+-----", "")
            .replaceAll("-----END [^-]+-----", "")
            .replaceAll("\\s+", "");
    if (body.isEmpty()) throw new IllegalArgumentException("empty or malformed PEM");
    return Base64.getDecoder().decode(body);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  /**
   * Encrypt a UTF-8 string to a customer RSA public key (SPKI PEM), returning the at-rest
   * envelope serialized as a compact JSON string. Local — no network call. Zero-knowledge:
   * only the public key is needed; decryption requires the matching private key.
   */
  public String encrypt(String plaintext, String publicKeyPem) {
    try {
      byte[] spki = pemToDer(publicKeyPem);
      PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
      String fp = toHex(MessageDigest.getInstance("SHA-256").digest(spki));

      SecureRandom rng = new SecureRandom();
      byte[] aesKey = new byte[32];
      rng.nextBytes(aesKey);
      byte[] iv = new byte[12];
      rng.nextBytes(iv);

      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
      byte[] ciphertext = aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
      rsa.init(Cipher.ENCRYPT_MODE, pub, oaepSha256());
      byte[] wrappedKey = rsa.doFinal(aesKey);

      Base64.Encoder b64 = Base64.getEncoder();
      StringBuilder sb = new StringBuilder();
      sb.append("{\"v\":1,\"keyAlg\":\"").append(ENC_ALG).append("\",\"fp\":\"").append(fp);
      sb.append("\",\"enc\":\"A256GCM\",\"iv\":\"").append(b64.encodeToString(iv));
      sb.append("\",\"wrappedKey\":\"").append(b64.encodeToString(wrappedKey));
      sb.append("\",\"ciphertext\":\"").append(b64.encodeToString(ciphertext)).append("\"}");
      return sb.toString();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("encrypt failed: " + e.getMessage(), e);
    }
  }

  /**
   * Decrypt an at-rest envelope (JSON string) with the customer's RSA private key (PKCS8 PEM),
   * returning the original UTF-8 plaintext. Local — no network call.
   */
  @SuppressWarnings("unchecked")
  public String decrypt(String envelope, String privateKeyPem) {
    try {
      Map<String, Object> env = (Map<String, Object>) Json.parse(envelope);
      Base64.Decoder b64 = Base64.getDecoder();
      byte[] iv = b64.decode((String) env.get("iv"));
      byte[] wrappedKey = b64.decode((String) env.get("wrappedKey"));
      byte[] ciphertext = b64.decode((String) env.get("ciphertext"));

      byte[] pkcs8 = pemToDer(privateKeyPem);
      PrivateKey priv =
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

      Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
      rsa.init(Cipher.DECRYPT_MODE, priv, oaepSha256());
      byte[] aesKey = rsa.doFinal(wrappedKey);

      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
      byte[] pt = aes.doFinal(ciphertext);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("decrypt failed: " + e.getMessage(), e);
    }
  }
}
