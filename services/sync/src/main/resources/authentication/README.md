# Password reference data

Gradle fetches the pinned SecLists common/breached Xato password list at build time, verifies its
source checksum, and generates SHA-256 digests of the first 10,000 distinct eligible passwords in
upstream frequency order. The source contains 5,189,454 password lines; 141,033 meet the selected
15–128 Unicode scalar and 1,024 UTF-8 byte bounds. The exact source revision, path, source hash,
artifact hash and license hash are in the manifest. The upstream MIT license is included in
LICENSE.txt. No source plaintext is committed or included in the application archive.

The digest file contains lowercase hex digests sorted lexicographically, with one LF per entry.
Derivation decodes strict UTF-8, removes only a trailing LF and its optional preceding CR, counts
Unicode scalar values, filters the accepted bounds, keeps the first 10,000 distinct eligible entries,
and hashes each exact remaining UTF-8 value. No trimming, case folding or Unicode normalization is
performed. Gradle verifies the generated artifact against the pinned checksum before packaging it.
The application loader verifies count, syntax, uniqueness, order and the same checksum before use.
The verified upstream source is cached under Gradle's user home so later builds can use the cache
without another download. A fresh build without that cache requires network access; the running
server performs no network lookup and can operate offline.

Source: https://github.com/danielmiessler/SecLists/blob/c205c36a445bff37f8e58a9ec829105cd4975c58/Passwords/Common-Credentials/xato-net-10-million-passwords.txt

Reference digests are public weak-password data, not stored account verifiers or active credentials.
Only setup/change/reset consult the set; login checks the account verifier for the exact received text.
No runtime network lookup, password-prefix query or reference match diagnostic is permitted. Corpus
updates must review upstream license/provenance, regenerate the manifest and update both build-time
and runtime artifact checksums.
