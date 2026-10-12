# Offline password reference data

The bundled artifact contains SHA-256 digests of the first 10,000 distinct eligible passwords, in
upstream frequency order, from SecLists' common/breached Xato password list. The source contains
5,189,454 password lines; 141,033 meet the selected 15–128 Unicode scalar and 1,024 UTF-8 byte bounds.
The exact source revision, path, source hash, artifact hash and license hash are in the manifest.
The upstream MIT license is included in LICENSE.txt. No source plaintext is bundled.

The digest file contains lowercase hex digests sorted lexicographically, with one LF per entry.
Derivation decodes strict UTF-8, removes only a trailing LF and its optional preceding CR, counts
Unicode scalar values, filters the accepted bounds, keeps the first 10,000 distinct eligible entries,
and hashes each exact remaining UTF-8 value. No trimming, case folding or Unicode normalization is
performed. The published manifest and loader integrity test make the derived artifact reproducible.
The startup loader verifies count, syntax, uniqueness, order and the pinned SHA-256 before use.

Source: https://github.com/danielmiessler/SecLists/blob/c205c36a445bff37f8e58a9ec829105cd4975c58/Passwords/Common-Credentials/xato-net-10-million-passwords.txt

Reference digests are public weak-password data, not stored account verifiers or active credentials.
Only setup/change/reset consult the set; login checks the account verifier for the exact received text.
No runtime network lookup, password-prefix query or reference match diagnostic is permitted. Corpus
updates must review upstream license/provenance, regenerate the manifest and pin the new loader hash.
