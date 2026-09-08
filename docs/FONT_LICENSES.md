# Bundled font licenses

MegaPodcastPlayer bundles two variable typefaces as Android font resources in
`core/designsystem/src/main/res/font/`. Both are licensed under the
SIL Open Font License, Version 1.1.

| Resource | Family | Upstream |
|---|---|---|
| `bricolage_grotesque.ttf` | Bricolage Grotesque | https://github.com/ateliertriay/bricolage |
| `inter.ttf` | Inter | https://github.com/rsms/inter |

Both files are the unmodified variable builds published in the
[google/fonts](https://github.com/google/fonts) repository.

## Where the license text lives

**`core/designsystem/src/main/res/raw/font_licenses.txt`**, and only there. It used to be
reproduced in this file as well, which meant the APK — the thing actually being distributed —
carried the fonts and not the license the OFL requires to travel with them, while the copy that did
exist was in a repository the recipient never sees.

Settings ▸ About ▸ *Font licences* reads that raw resource and displays it verbatim (SET-4). A
second copy here would be a second thing to keep in step, so this file points at it instead.

Updating a font means replacing the `.ttf` **and** the corresponding section of that resource.
