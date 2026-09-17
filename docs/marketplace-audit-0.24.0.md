# Marketplace readiness audit: 0.24.0

**Assessment: technical checks passed; submission readiness is not established.** Resolve the license and data-handling items below before submission. This is a local pre-submission review, not a JetBrains approval, a legal opinion or a penetration test. No plugin was submitted to Marketplace.

Audited on **2026-09-17**, after pushing [789748b](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/commit/789748b9a1e2a7e01aebae469fcbde1409d98074) to `main`. The subsequent audit-documentation commit does not change the ZIP.

| Artifact | Value |
| --- | --- |
| Display name | Spring Boot Debug REPL and MCP |
| Stable plugin ID | `hu.baader.java-over-nrepl` |
| Version | `0.24.0` |
| Installable archive | `build/distributions/sb-repl-0.24.0.zip` |
| Archive size | 6,579,268 bytes |
| SHA-256 | `c37ba02a0fc70ef7a4a1aeabe46c66214ad4866c203fbabd758d15adfadcec27` |
| Declared IDEA range | `241.0` through `252.*` |

## Automated verification

| Check | Observed result |
| --- | --- |
| Gradle `check buildPlugin`, JDK 17 | Passed |
| Gradle `check`, JDK 21 | Passed |
| Regression suite | 338 tests per JDK: 138 plugin, 14 full-JDK integrations, 186 runtime; no failures, errors or skips |
| `verifyPluginStructure` | Passed |
| IDEA Community 2024.1.4, build 241.18034.62 | Compatible; 9 deprecated API usages |
| IDEA Ultimate 2025.1.7, build 251.29188.11 | Compatible; 18 deprecated usages and 1 scheduled-removal usage |
| IDEA Community 2025.2, build 252.23892.409 | Compatible; 20 deprecated usages and 1 scheduled-removal usage |
| IDEA Ultimate 2025.2, build 252.23892.409 | Compatible; 20 deprecated usages and 1 scheduled-removal usage |
| Maven agent and bridge packaging | Both passed; agent isolation/package check passed |
| Release consistency | Gradle, Maven POMs, MCP server info and documentation use 0.24.0; `--release-tag v0.24.0` check passed |
| ZIP inspection | Version, name, stable ID, bundled agent and both handbook copies matched the build outputs |
| Handbook review | English and Hungarian covers rendered and visually checked; 69 pages each |
| Credential-pattern scan | No private-key, GitHub-token or AWS-access-key markers found in tracked text files; limited pattern check, not a secret-detection guarantee |

The verifier reported no binary-compatibility errors or internal-API findings in these runs. Deprecation notices remain maintenance work; they did not make these four verdicts incompatible. The scheduled-removal item includes the toolbar's `displayTextInToolbar()` override. The supported range intentionally excludes IDEA 2025.3 and 2026 releases; compatibility with those versions is not claimed.

[Plugin Verifier checks binary compatibility](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html). It does not establish end-to-end correctness in every application, UI responsiveness under load or Marketplace approval.

## Items to resolve before submission

### 1. Plugin-wide license/EULA is not evidenced

**Priority: submission prerequisite.** Neither the tracked repository nor the main plugin JAR contains a plugin-wide LICENSE/EULA. The two Maven POMs declare MIT, but that metadata does not supply the IDE plugin's end-user terms or establish the copyright holder for the whole repository.

The owner must confirm the intended license and rights, provide the actual terms, and populate the Marketplace license field. If open source, provide the source repository link. Do not infer or invent the owner's licensing decision from the public repository. A license is required for Marketplace publication. [Listing guidance](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)

The nested Byte Buddy 1.14.9 and byte-buddy-agent 1.14.9 JARs retain their `META-INF/LICENSE` and `NOTICE` files; Byte Buddy also retains its ASM notice. An aggregate third-party notice would make these easier to review. Rights to the logo and embedded handbook fonts were not independently established.

### 2. Data-handling disclosure and consent coverage need review

**Priority: submission prerequisite review.** There is no dedicated privacy/data-handling document in the repository or archive. The manuals describe individual safeguards, but the submission should clearly explain:

- Optional AI requests send the reviewed prompt to the configured endpoint; an API key can come from PasswordSafe or `OPENAI_API_KEY`.
- Local MCP clients can receive source, objects and captured values according to the permissions selected by the user. A client may subsequently forward them elsewhere.
- Snapshots, recordings, workspace checkpoints and audit logs can retain application data. Workspace checkpointing is automatic; history persistence defaults to off. Redaction is heuristic and is not anonymization; exported bundles are not encrypted.
- Retention, local storage locations, deletion controls, recipients and a contact for data-related questions.

Confirm whether the vendor collects personal data and supply a privacy policy where applicable. Review the user notices/consent for each processing path; do not label the product as having no data processing merely because much of it is local. The current AI panel exposes the exact prompt and requires **Send reviewed prompt**; generated Java requires a separate insertion and execution. This is a useful control, not evidence that every consent obligation is covered.

JetBrains requires explicit permission for personal/statistical/telemetric-data processing and a privacy policy when the developer collects personal data. [Approval guidelines, sections 2.2 and 3](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)

### 3. Account and brand checks cannot be completed from the repository

**Priority: submission prerequisites to confirm.** The display name is exactly **30 characters**, within the current approval-guideline limit. Marketplace uniqueness and permission to use third-party marks still require confirmation. The stricter dated approval guidelines take precedence over the more permissive name-length wording in the listing-tips page.

The packaged vendor URL is `https://baader.hu`; it was reachable and identified the author. The packaged contact is `support@baader.hu`; mailbox delivery was not tested. Confirm the actual vendor profile, developer-agreement acceptance, trader/non-trader declaration and contact details in the publishing account. No account declarations were made on the owner's behalf. [Approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)

## Presentation and maintenance findings

| Finding | Evidence and recommended action |
| --- | --- |
| Raster image wrapped in SVG | Both `META-INF/pluginIcon*.svg` files are 40×40, but each embeds a roughly 40-pixel PNG using a data URI. Replace with a real vector version of the approved logo; check 40/80-pixel rendering on light/dark backgrounds and transparent padding. This is a quality/readiness concern, not a verifier rejection observed in this audit. |
| Repeated packaged description | `plugin.xml` and `build.gradle.kts` both supply a description; the inspected ZIP contains both paragraphs appended. Keep one authoritative English HTML description, with a short value statement and documentation/support links. |
| Deprecated IDE APIs | The verifier lists the counts above. Prioritize scheduled-removal APIs when changing the oldest supported IDE; retain behavior/UI tests. |
| Distribution signing | This ZIP is unsigned and no author-signing configuration is present. Decide the release signing procedure; protect keys outside source control. Unsigned does not by itself prove Marketplace rejection. |
| Installation smoke test | Headless IDE integration tests include UI construction/rendering, editor features and run-config contracts. A fresh interactive install/update/uninstall with a representative Spring application on each target OS was not performed for this artifact. Include checkbox/profile preservation, MCP start/stop and IDE responsiveness in that pass. |

The SDK asks for a small, scalable SVG logo with transparent padding and light/dark visibility. [Logo requirements](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html) Signing is a separate publishing mechanism; the Gradle signing task may be skipped when credentials are absent. [Signing documentation](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)

## Security and dependency observations

The inspected production paths use loopback listeners, random bearer tokens, host/origin validation for MCP, bounded request/result sizes and separate MCP sessions. Execution, HotSwap and snapshot mutations default to disabled in `McpPermissions`. MCP starts explicitly and stops when its REPL target changes. The runtime endpoint file uses owner-only POSIX permissions where available. Credentials use IDE PasswordSafe; AI requests enforce HTTPS outside loopback. No automatic telemetry destination was found in the reviewed network clients.

These are source-review observations backed by existing tests, not a security certification. Authorized Java runs with application privileges; read-only/rollback settings are not a Java sandbox. Local audit files are not tamper-proof. Windows file-permission behavior and hostile-client/load testing need separate validation.

An [OSV query](https://osv.dev/) on 2026-09-17 returned no advisories for the distributed `net.bytebuddy:byte-buddy:1.14.9`, `net.bytebuddy:byte-buddy-agent:1.14.9`, and its embedded `org.ow2.asm:asm:9.6`. This limited package/version lookup excludes IDE-provided libraries, application dependencies and undisclosed vulnerabilities.

## Reproduce the technical checks

```sh
python3 scripts/check-repository.py --release-tag v0.24.0
./gradlew --offline check buildPlugin -PtestJdk=17
./gradlew --offline check -PtestJdk=21
./gradlew --offline verifyPlugin verifyPluginStructure -PtestJdk=21
./gradlew --offline verifyPlugin -PtestJdk=21 -PverifyPreviousIdes=true
mvn -o -B -f sb-repl-agent/pom.xml package -Dgpg.skip=true
python3 scripts/check-agent-package.py sb-repl-agent/target/sb-repl-agent-0.24.0.jar
mvn -o -B -f sb-repl-bridge/pom.xml package -Dgpg.skip=true
```

Offline flags require a populated dependency/IDE cache; omit them on a fresh machine. Generated evidence is in `build/release-0.24-*`, `build/marketplace-0.24-*` and `build/reports/pluginVerifier/`. The checked-in report records the results; generated logs are intentionally not source files. GitHub Actions provides a separate Linux check; its [release-commit run](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/actions/runs/35178672968) was still running during this review.

After resolving submission prerequisites, upload through the owner's Marketplace account with the license, applicable privacy policy, verified contact details and representative screenshots. Every version undergoes JetBrains review; the final decision remains theirs. [Publishing review process](https://plugins.jetbrains.com/docs/marketplace/publishing-and-listing-your-plugin.html)
