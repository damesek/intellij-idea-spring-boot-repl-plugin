# Marketplace release preparation

Release candidate: **0.25.0**, on the **jetbrains-idea-plugin** branch of [damesek/intellij-idea-spring-boot-repl-plugin](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/tree/jetbrains-idea-plugin). No Marketplace submission has been performed by this preparation.

## Listing

**Name:** Spring Boot Debug REPL and MCP

**Plugin ID:** `hu.baader.java-over-nrepl`

**License:** Eclipse Public License 2.0 (`EPL-2.0`), selected by the owner. [Full terms](../LICENSE) and [copyright/source notice](../NOTICE).

**Short summary:** Java workbooks in your running Spring Boot application, with a complete local MCP toolset for AI clients.

The English HTML description in `src/main/resources/META-INF/plugin.xml` is the single authoritative listing description. Build configuration supplies version and change notes. The interface has three primary tabs: Workbook, MCP and Tools. All ten supporting panels and all 82 MCP tools remain available. Advanced MCP settings can be opened on demand without changing their values or defaults.

**Documentation:** [README and installation](../README.md), [English PDF](../output/pdf/spring-boot-repl-guide-en.pdf), [Hungarian PDF](../output/pdf/spring-boot-repl-guide-hu.pdf).

**Data handling:** [PRIVACY.md](../PRIVACY.md).

**Support:** support@baader.hu and the [issue tracker](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/issues). Confirm the mailbox before submission.

**Third-party terms:** [THIRD-PARTY-NOTICES.txt](../THIRD-PARTY-NOTICES.txt); original Byte Buddy/ASM terms remain in the bundled libraries. Both PDF manuals contain the embedded DejaVu font notices.

## Package and validation

- Upload the installable ZIP from `build/distributions/sb-repl-0.25.0.zip`, rather than a source archive or individual JAR.
- The supported IDEA build range remains `241.0-252.*`: IntelliJ IDEA 2024.1 through 2025.2.x. No compatibility is claimed for later IDE versions.
- The full suite passed on both JDK 17 and 21: 345 tests per JDK (145 plugin tests, 14 full-JDK integration tests and 186 runtime tests), with no failures, errors or skipped tests. The Maven agent and bridge packages also built successfully.
- Source snapshot points have both their breakpoint type and Java debugger handler registered. Tests cover the registration and DATA capture expressions; the runtime integration launches a real debug JVM. Interactive confirmation should trigger a new application call after creating a purple point and check the Debug console receipt and Tools > Snapshots > Saved > Refresh.
- The light and dark icons are original scalable SVGs with transparent padding. The manuals use DejaVu fonts with their notices preserved.
- UI renders produced by the IntelliJ tests are previews of disconnected screens. For the public listing, capture an actual connected Workbook result, MCP running state and a useful Tools view. Do not represent synthetic test renders as a live application session.

### Verified package, 2 October 2026

Plugin Verifier checked this release ZIP with the following results:

| IDE | Result | Remaining API notices |
| --- | --- | --- |
| Community 2024.1.4 (`241.18034.62`) | Compatible | 9 deprecated usages |
| Ultimate 2025.1.7 (`251.29188.11`) | Compatible | 18 deprecated usages, 1 scheduled for removal |
| Community 2025.2 (`252.23892.409`) | Compatible | 20 deprecated usages, 1 scheduled for removal |
| Ultimate 2025.2 (`252.23892.409`) | Compatible | 20 deprecated usages, 1 scheduled for removal |

The remaining removal notice concerns `AnAction.displayTextInToolbar()` in the existing workbook menu. These notices should be addressed when extending compatibility to newer IDE versions; the current descriptor does not include those versions.

The EPL-licensed ZIP is **6,557,117 bytes**, SHA-256 `6c3b8c80b5ddb10c293f34853f2f33fa9cd44b8f1264137409500dbd8690802b`. Inspection confirmed the stable plugin ID, version, supported build range, Java snapshot handler registration, original SVG icons, privacy/third-party notices and both manuals. LICENSE and NOTICE are readable directly under the ZIP's `sb-repl/` directory and included in the plugin, protocol and bundled agent JARs. Both Maven binary packages carry the same terms and matching EPL 2.0 metadata. The plugin classpath has no duplicate classes and does not bundle the IntelliJ SDK or Spring. The agent manifest and private Byte Buddy libraries passed packaging checks, including retained library notices. All executable classes and private third-party libraries are byte-identical to the preceding 0.25.0 candidate; the 82 unique MCP tool definitions and permissions are unchanged.

The English manual has 72 pages and the Hungarian manual 76 pages, with 52 outline entries in each. The repository and bundled PDF bytes match; source digests, every rendered page and page-boundary checks were verified. A connected interactive source-snapshot test remains a recommended release check.

## Publisher-owned submission steps

1. **Provide the selected EPL 2.0 terms and source link.** Licensing is finalized: the repository contains the unchanged official EPL 2.0 text, the source/copyright notice and matching Maven license metadata. The installable ZIP includes readable LICENSE and NOTICE files and copies in the project JARs, including the bundled agent. Use the [license URL](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/blob/jetbrains-idea-plugin/LICENSE) and [public source URL](https://github.com/damesek/intellij-idea-spring-boot-repl-plugin/tree/jetbrains-idea-plugin) in the Marketplace form. Third-party terms remain intact.
2. **Confirm the publisher identity and contact information.** Use the owner's JetBrains Marketplace account, accept the Developer Agreement, and complete the required trader/non-trader declaration. This preparation does not make those declarations for the owner.
3. **Review the privacy statement and representative screenshots.** The optional AI and MCP paths can share application data; select the applicable privacy information in the submission form and link the data-handling document.
4. **Upload the tested ZIP and listing metadata.** If this plugin ID is already owned by the publisher, upload an update to that entry. Otherwise create its first entry with the same stable ID. Signing can be configured separately when the publisher supplies the signing material.
5. **Wait for JetBrains review.** Uploading is not automatic approval or immediate public availability. Keep the supported IDE range accurate and address any reviewer feedback.

Official references: [Prepare for publication](https://plugins.jetbrains.com/docs/marketplace/prepare-your-plugin-for-publication.html), [Upload a plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html), [Approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html), [Icon requirements](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html), [Plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
