import "@typespec/http";
import "@typespec/openapi";
import "@typespec/json-schema";

import "./sync-models.tsp";
import "./sync-operations.tsp";

using TypeSpec;
using TypeSpec.OpenAPI;

@TypeSpec.service(#{ title: "Hortinis Synchronization API" })
@info(#{ version: "1.0.0" })
@TypeSpec.doc("The contract-first HTTP API for the Hortinis synchronization service.")
namespace Hortinis.Sync;
