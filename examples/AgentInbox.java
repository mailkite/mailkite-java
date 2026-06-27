// Give your agent its own email address — MailKite's built-in inbox agent answers mail for you.
//
// Point a route's action at the hosted `agent`: every email to the matched address is handed to an
// inbox agent that reads it and acts on your instructions — reply, file, or escalate. No webhook
// server required.
//
// Run:  MAILKITE_API_KEY=mk_live_… java -cp mailkite.jar AgentInbox.java

import dev.mailkite.MailKite;
import java.util.Map;

public class AgentInbox {
  public static void main(String[] args) {
    MailKite mk = new MailKite(System.getenv("MAILKITE_API_KEY"));

    // The domain must already be verified (mk.createDomain + DNS + mk.verifyDomain — see the docs).
    Object route = mk.createRoute(Map.of(
        "match", "support@yourdomain.com",   // or "*@agent.yourdomain.com" for a whole subdomain
        "action", "agent",
        "agentPrompt",
        "You are Acme's email support agent. Answer billing and account questions from our docs, "
            + "keep replies short and friendly, and escalate anything you're unsure about by "
            + "forwarding to team@yourdomain.com. Never share account secrets."
    ));
    System.out.println("inbox agent live: " + route);

    // Test it without sending real mail — hand the agent a message directly and read its reply:
    Object reply = mk.agent(Map.of(
        "to", "support@yourdomain.com",
        "text", "Hi, how do I reset my password?"
    ));
    System.out.println("agent says: " + reply);
  }
}
