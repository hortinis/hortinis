# Web application architecture

The repository contains one client-rendered Angular PWA. Features and supporting
capabilities live inside `src/app`. Services coordinate workflows; dedicated
persistence components use Dexie to store local data in IndexedDB. The Angular
service worker caches the application shell for offline use.

Retained decisions:

- [Web application and local persistence](decisions/0008-web-and-local-persistence.md)
- [Plant catalog distribution contract](decisions/0014-plant-catalog-distribution.md)
- [Project name and license](decisions/0016-project-name-and-license.md)
- [Repository language](decisions/0018-repository-language.md)
- [Web styling foundation](decisions/0019-web-styling-foundation.md)
- [UUIDv7 library](decisions/0022-uuid-v7-library.md)

Backend, synchronization, and broader implementation plans were removed when the
repository was reduced to the web application.
