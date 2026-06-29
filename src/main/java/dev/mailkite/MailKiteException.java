package dev.mailkite;

/** Raised when the API returns a non-2xx response (or the request fails). */
public class MailKiteException extends RuntimeException {
  public final int status;
  public final transient Object body;

  public MailKiteException(int status, String message, Object body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}
