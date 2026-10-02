# Data handling and privacy

Spring Boot Debug REPL and MCP, version 0.25.0. Updated October 2, 2026.

The plugin works with source code and values inside your development application. Those values may include personal or confidential data. This document explains where the plugin stores them, when another service can receive them, and how retained copies can be removed.

## Network access and sharing

The plugin does not implement publisher telemetry, advertising, usage analytics or automatic uploads to Baader. Installing or opening the plugin does not send your source code or runtime values to the publisher.

- **Application connection:** the bundled agent publishes an authenticated local endpoint. The IDE sends the Java expressions and operations you request to the selected application. The agent can also be attached explicitly to a running JVM. Your application may make its own network or database calls during execution.
- **MCP:** you explicitly start a server listening on `127.0.0.1`. Authenticated clients receive results allowed by the selected category permissions and individual tool list. Java execution, snapshot writes/deletion, CASE execution, capture changes, HotSwap and sharing IDE recordings are opt-in. Each client has a separate REPL session, while application state and persistent DATA snapshots are shared. A connected AI client can forward the data it reads to its own AI provider; that client's configuration and provider terms determine further processing.
- **Optional AI panel:** **Tools > AI > Prepare prompt** shows the exact editable prompt, including the bean metadata and request you selected. Only **Send reviewed prompt** sends it to the provider endpoint configured in Settings. The default endpoint is the OpenAI API. The request includes the configured model and API credential. There is no automatic sending of the workbook or the entire project. Generated code needs separate insertion and execution.
- **HTTP:** **Tools > HTTP** sends a request to the URL you selected only when you run it. The destination receives its headers, body and authentication values. Generating or inserting a snippet does not send it.
- **Exports and support:** saving a workspace, snapshot, recording, reproduction or JUnit source writes the selected contents to the file you choose. The plugin does not automatically upload that file. If you submit a support report, the publisher receives the content you include in it.

AI-provider and MCP-client retention policies are separate from this plugin. Review the displayed prompt and the client's permissions before sharing application data. Secret redaction detects known patterns and is not exhaustive personal-data anonymization.

## Local storage and retention

| Data | Storage and retention |
| --- | --- |
| Workbook recovery | A per-project `checkpoint.json` under the IDE system directory's `java-repl-workspaces/<project-location-hash>/`. It can contain Java cells, bounded cell output, bookmarks, snapshot binding references and other workspace metadata. Workspace contents already imported or selected for export can include transcript/HTTP data. Recovery is written automatically after changes and on disposal; no automatic expiry is applied. |
| Persistent snapshots | DATA, RECIPE and CASE JSON, provenance and versions under the **application JVM user's** `~/.java-repl-snapshots/v1/<application-hash>/`. Values may include the business data you selected. They persist across application and IDE restarts until deleted. |
| LIVE references and recordings | LIVE pins belong to a session and expire after 30 minutes or session/context closure. Active runtime recordings and result handles are memory data. Explicitly saved recording files and exported copies persist at the selected locations. |
| Snippet history | Up to 200 bounded, redacted entries. Disk persistence is off by default and can be enabled in Settings. When enabled, history uses the project's `java-over-nrepl-history.xml` state. The lower workbook transcript and recovery/exported output are separate copies. |
| Audit logs | Redacted operation records under `~/.java-repl-audit`, or the directory selected with `sb.repl.audit.dir`. Rotation occurs at 4 MiB with five previous files per channel in addition to the current file. Logs contain operation/session metadata and bounded summaries; there is no age-based automatic deletion. The IDE and application may run under different users or directory settings. |
| Credentials | The AI key, saved HTTP request contents and project MCP token use IntelliJ PasswordSafe. Persistence and encryption follow the IDE's password-storage settings. An environment variable can supply the AI key. Copy client config puts the MCP token on the clipboard for the explicit copy action. |
| Settings and metadata | Application/plugin settings, import aliases and selected request metadata use IntelliJ state files. The remembered MCP port is in private project workspace state. Debugger snapshot points use the project's breakpoint state. |

Snapshot files, workspace archives and code outputs are not encrypted by the plugin. Private file permissions are applied where supported. Snapshots are deliberately preserved as selected data rather than automatically scrubbed of fields needed for reproduction.

## Removing retained data

1. **Snapshots:** use **Tools > Snapshots > Saved > Delete** to remove a snapshot and its saved versions. Delete separately exported copies from their chosen locations.
2. **History:** use **Workbook > Workspace > Clear history** and disable history persistence if it is not wanted. This also clears the current transcript, but previously exported files and recovery copies can retain earlier output.
3. **Recovery and audit files:** close the project and application, then delete the relevant `java-repl-workspaces` checkpoint and audit folders described above. Closing first prevents the current session from writing another recovery copy. A later new session can create fresh files.
4. **HTTP requests and AI credentials:** delete saved requests in Tools > HTTP; clear the API key in plugin Settings. Remove any environment-supplied key from your own environment configuration if needed.
5. **MCP credentials:** stop MCP and remove its `Spring Boot REPL: mcp:<project-location-hash>` credential using your configured IDE/OS password store. Starting MCP without the saved credential creates a replacement token; previously copied configurations need updating. Remove copied tokens from client configurations that you no longer use.
6. **Shared copies:** separately remove exported files, support attachments and copies held by an AI client/provider according to their own controls.

Uninstalling the plugin does not automatically delete snapshots, exports, audit logs, recovery checkpoints or PasswordSafe credentials. They are retained so development data and stable client credentials are not lost during an update or reinstall.

## Contact

Publisher: **Baader**. Support and privacy contact declared by the plugin: **support@baader.hu**. Issues: [GitHub issue tracker](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/issues).
