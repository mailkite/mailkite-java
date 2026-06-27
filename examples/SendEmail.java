// Send an email over a verified domain — the 10-second "it works".
//
// Run:  MAILKITE_API_KEY=mk_live_… java -cp mailkite.jar SendEmail.java
// (Java 11+: `java SendEmail.java` with the MailKite jar on the classpath.)

import dev.mailkite.MailKite;
import java.util.Map;

public class SendEmail {
  public static void main(String[] args) {
    MailKite mk = new MailKite(System.getenv("MAILKITE_API_KEY"));

    Object res = mk.send(Map.of(
        "from", "hello@yourdomain.com",   // an address on a domain you've verified
        "to", "ada@example.com",
        "subject", "Your invoice #1042",
        "html", "<p>Thanks for your order — receipt attached.</p>"
        // text, cc, bcc, replyTo, attachments, templateId, templateData all supported
    ));

    System.out.println("sent: " + res);  // → { id=msg_…, status=queued }
  }
}
