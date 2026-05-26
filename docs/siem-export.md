# SIEM / Syslog Export

LDAP Portal can forward audit events in real-time to a SIEM collector, syslog server, or webhook endpoint. Every action recorded in the audit log — user creates, group membership changes, access review decisions, approval workflows — is sent as it happens.

## Setup

1. Log in as a superadmin and go to **Settings**.
2. Scroll to the **SIEM / Syslog Export** section.
3. Check **Enable SIEM export**.
4. Choose a **Protocol** and **Format** (see below).
5. Fill in the connection details for your chosen protocol.
6. Click **Test Connection** to verify delivery.
7. Click **Save Settings**.

Events will start flowing immediately after saving.

## Protocols

| Protocol | When to use | Connection fields |
|---|---|---|
| **Syslog (UDP)** | Fast, fire-and-forget delivery to a local or network syslog collector. No delivery guarantee. | Host, Port (default 514) |
| **Syslog (TCP)** | Reliable delivery with connection-oriented transport. Uses RFC 6587 octet-counting framing. | Host, Port (default 514) |
| **Webhook (HTTPS)** | POST events as JSON to any HTTP endpoint — Splunk HEC, Datadog, Elastic, custom APIs. | Webhook URL, Authorization header |

## Formats

| Format | Best for | Example |
|---|---|---|
| **RFC 5424** | Standards-compliant syslog collectors (rsyslog, syslog-ng, Graylog) | `<134>1 2026-03-24T10:30:00Z - LDAP Portal - USER_CREATE [event@0 action="USER_CREATE" actor="alice" targetDn="uid=alice,ou=users,dc=corp"] USER_CREATE uid=alice,ou=users,dc=corp` |
| **CEF** | ArcSight, QRadar, Microsoft Sentinel, and other SIEM platforms that consume Common Event Format | `CEF:0\|LDAP Portal\|LDAP Portal\|1.0\|USER_CREATE\|USER_CREATE\|3\|suser=alice cs1=uid=alice,ou=users,dc=corp cs1Label=targetDn` |
| **JSON** | Splunk HEC, Elastic, Datadog, custom webhook consumers, or anything that parses structured JSON | `{"action":"USER_CREATE","actorUsername":"alice","targetDn":"uid=alice,ou=users,dc=corp",...}` |

## Common configurations

### rsyslog / syslog-ng

- Protocol: **Syslog (UDP)** or **Syslog (TCP)**
- Format: **RFC 5424**
- Host: your syslog server hostname
- Port: 514 (UDP) or 601 (TCP), or whatever your collector listens on

### Splunk HTTP Event Collector

- Protocol: **Webhook**
- Format: **JSON**
- Webhook URL: `https://splunk.example.com:8088/services/collector/event`
- Authorization header: `Splunk your-hec-token`

### Elastic / Logstash

- Protocol: **Syslog (TCP)** with **RFC 5424** format, pointed at a Logstash syslog input
- Or: **Webhook** with **JSON** format, pointed at an Elastic HTTP input or Logstash HTTP input

### Datadog

- Protocol: **Webhook**
- Format: **JSON**
- Webhook URL: `https://http-intake.logs.datadoghq.com/api/v2/logs`
- Authorization header: `DD-API-KEY your-api-key`

## Authentication

- **Syslog**: An optional auth token field is available for collectors that require token-based authentication.
- **Webhook**: The Authorization header value is sent with every request. Supports any scheme (Bearer, Basic, API key, custom).

All credentials are stored AES-256 encrypted at rest.

## Behavior notes

- Events are sent asynchronously. A slow or unreachable SIEM target will not block LDAP operations or audit recording.
- If delivery fails, the error is logged locally but the event is not retried. The event is always recorded in the LDAP Portal database regardless of SIEM delivery status.
- Disabling SIEM export stops forwarding immediately. Previously sent events are not affected.
- The test button sends a synthetic event so you can verify your pipeline end-to-end without making real changes.

## API

SIEM configuration is managed through the application settings endpoints:

- `GET /api/v1/settings` — includes SIEM fields in the response
- `PUT /api/v1/settings` — update SIEM configuration along with other settings
- `POST /api/v1/settings/siem/test` — send a test event and check connectivity
