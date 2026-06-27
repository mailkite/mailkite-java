# MailKite examples — Java

Runnable, copy-pasteable examples. Each file is a standalone `public class` with a `main`; the
header comment's first line says what it does and how to run it.

All five need the MailKite jar on the classpath (`dev.mailkite:mailkite` from Maven Central, or a
local build). `AgentEmailReply` additionally needs the official Anthropic Java SDK
(`com.anthropic:anthropic-java`). The webhook and login servers use only the JDK's
`com.sun.net.httpserver.HttpServer` — no web framework required.

| File | What it shows |
| --- | --- |
| [`SendEmail.java`](SendEmail.java) | Send an email over a verified domain |
| [`ReceiveWebhook.java`](ReceiveWebhook.java) | Receive inbound mail as a webhook and **verify the HMAC signature** |
| [`AgentEmailReply.java`](AgentEmailReply.java) | **AI email agent** — inbound email → Claude drafts a reply → MailKite sends it, threaded |
| [`AgentInbox.java`](AgentInbox.java) | Give your agent its own address with MailKite's **built-in inbox agent** (no server) |
| [`ServerLogin.java`](ServerLogin.java) | **Server-side login + register** — your own account, or your users' accounts via OAuth |

Full docs: <https://mailkite.dev/docs> · AI agents: <https://mailkite.dev/docs/ai-agents>
