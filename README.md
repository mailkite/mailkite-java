# MailKite for Java

Official [MailKite](https://mailkite.dev) SDK. One low-level `request()` plus one
method per endpoint. Zero dependencies (uses `java.net.http` + a bundled JSON
helper). Requires Java 11+.

## Install

Maven:

```xml
<dependency>
  <groupId>dev.mailkite</groupId>
  <artifactId>mailkite</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Usage

```java
import dev.mailkite.MailKite;
import java.util.Map;

MailKite mk = new MailKite(System.getenv("MAILKITE_API_KEY"));

Object res = mk.send(Map.of(
    "from", "hello@myapp.ai",
    "to", "ada@example.com",
    "subject", "Your invoice #1042",
    "html", "<p>Thanks! Receipt attached.</p>"
));
```

Bodies and responses are plain `Map`/`List` values. Point at a different base URL
with `new MailKite(key, "https://api.mailkite.dev")`.

## Methods

`send(message)`, `agent(message)`, `route(message)`, `listDomains()`, `createDomain(body)`, `getDomain(id)`,
`deleteDomain(id)`, `verifyDomain(id)`, `setWebhook(id, body)`,
`deleteWebhook(id)`, `testWebhook(id)`, `checkDomainAvailability(domain)`,
`registerDomain(body)`, `listRoutes()`, `createRoute(body)`,
`listMessages()`, `getMessage(id)`, `retryDelivery(id)`,
`listTemplates()`, `listBaseTemplates()`, `getTemplate(id)`,
`createTemplate(body)`.

Non-2xx responses throw a `MailKiteException` with `status`, `getMessage()`, `body`.

See the [full docs](https://mailkite.dev/docs/libraries).
