# Third-party components

jmux’s original code is MIT licensed. Dependencies are not relicensed.

| Component | Version | License/source |
|---|---|---|
| JediTerm core fork | 3.76-ghosthack.2 | Apache-2.0 (upstream licensing option and fork changes); https://github.com/ghosthack/jediterm |
| pty4j | 0.13.10 | EPL-1.0; https://github.com/JetBrains/pty4j |
| Jackson databind and dependencies | 2.18.2 | Apache-2.0; https://github.com/FasterXML/jackson-databind |
| SLF4J API/simple | 2.0.17 | MIT; https://github.com/qos-ch/slf4j |
| Kotlin stdlib (terminal dependency) | 2.4.0 | Apache-2.0; https://github.com/JetBrains/kotlin |
| JetBrains annotations | 24.0.1 | Apache-2.0; https://github.com/JetBrains/java-annotations |
| JNA and native components (pty4j dependency) | resolved by pty4j | Upstream notices apply; https://github.com/java-native-access/jna |

The CLI includes dependent classes and native resources. License texts and
pty4j’s notices are bundled under `META-INF/licenses` in both jmux artifacts.
Consult each linked upstream project for dependency source and attribution.
The JDK is supplied by the builder; jmux does not bundle agent CLIs or logins.

The JediTerm core fork is consumed as
`io.github.ghosthack:jediterm-core:3.76-ghosthack.2` from Maven Central.
It selects upstream's Apache 2.0 licensing option; fork modifications are
also Apache 2.0. Its attribution and modification notices are retained in
`licenses/JEDITERM-NOTICE.txt`.
