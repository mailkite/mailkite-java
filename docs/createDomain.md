# `createDomain`

Add a domain. Returns the domain + DNS records.

**HTTP:** `POST /api/domains`

## Parameters

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `domain` | string | ✓ |  |

## Returns

`any`

## Example

```java
Object res = mk.createDomain(Map.of("domain", "app.mailkite.dev"));
```

---

[← All methods](../README.md#api-methods) · [Docs](https://mailkite.dev/docs) · [mailkite.dev](https://mailkite.dev)
