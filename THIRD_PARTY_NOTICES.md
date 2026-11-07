# Third-party notices

Stratos is MIT-licensed (see `LICENSE`). It bundles or depends on the
following third-party work; each retains its own license.

## Bundled fonts (app/src/main/res/font/)

- **Readex Pro** — Arabic-first geometric sans (Lexend lineage) by Zeyad
  Etman, Zainab Akhtar, Zohaib Akhtar and Zoya Raza (Lieto), extended by the
  Readex Pro team; covers Arabic, Turkish and Latin in one family. Static
  instances (400/500/600/700) generated from the upstream variable font.
  Licensed under the SIL Open Font License 1.1 with the Reserved Font Name
  "Readex Pro" — the full license text ships with the repository as
  `docs/licenses/ReadexPro-OFL.txt` and is also available at
  <https://github.com/google/fonts/blob/main/ofl/readexpro/OFL.txt>.
- **Space Grotesk** — Latin display family (numerals, URLs, wordmark) by
  Florian Karsten. Static instances (500/700) generated from the upstream
  variable font. Licensed under the SIL Open Font License 1.1 with the
  Reserved Font Name "Space Grotesk" — the full license text ships with the
  repository as `docs/licenses/Space-Grotesk-OFL.txt` and is also available
  at <https://github.com/google/fonts/blob/main/ofl/spacegrotesk/OFL.txt>.

  OFL fonts are not covered by the project's MIT license; they are bundled
  unmodified under their own terms.

## Bundled TextMate grammars (assets/textmate/)

- **HTML, CSS, JavaScript, JSON, Markdown, XML** grammars and language
  configurations — from [microsoft/vscode](https://github.com/microsoft/vscode)
  (`extensions/*/syntaxes`), licensed under the MIT License:
  Copyright (c) Microsoft Corporation. All rights reserved.
- **YAML** grammar and language configuration — from
  [Red Hat vscode-yaml](https://github.com/redhat-developer/vscode-yaml)
  (`syntaxes/yaml.tmLanguage.json`), MIT License,
  Copyright (c) Red Hat, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, subject to the above copyright notices and this
permission notice being included in all copies or substantial portions of the
Software.

## Gradle dependencies (unmodified, via Maven)

| Artifact | License |
|---|---|
| Jetpack Compose, Material 3, Navigation, Lifecycle, Activity | The Apache Software License 2.0 |
| androidx.core, appcompat, Room, DataStore | The Apache Software License 2.0 |
| Kotlin (stdlib, coroutines, kotlinx-serialization) | The Apache Software License 2.0 |
| OkHttp, OkHttp SSE (Square) | The Apache Software License 2.0 |
| KSP | The Apache Software License 2.0 |
| sora-editor (`editor`, `language-textmate`, io.github.Rosemoe) | LGPL-2.1-only |
| ZXing core | The Apache Software License 2.0 |
| NanoHTTPD | BSD 3-Clause |
| JUnit | Eclipse Public License 1.0 |

The Go engine (`engine/`) depends only on the Go standard library.

No binary is redistributed with this repository; all of the above are pulled
from Maven Central / Google Maven at build time by Gradle.
