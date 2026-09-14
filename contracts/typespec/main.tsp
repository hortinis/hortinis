import "@typespec/http";
import "@typespec/openapi";

using TypeSpec;
using TypeSpec.OpenAPI;

@TypeSpec.service(#{ title: "Hortinis Synchronization API" })
@info(#{ version: "0.0.0" })
@TypeSpec.doc("The contract-first HTTP API for the Hortinis synchronization service.")
namespace Hortinis.Sync;
