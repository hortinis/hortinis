import "@typespec/http";
import "@typespec/openapi";
import "@typespec/json-schema";

using TypeSpec.Http;
using TypeSpec.OpenAPI;

@TypeSpec.service(#{ title: "Hortinis Contract Emitter Probe" })
@TypeSpec.OpenAPI.info(#{ version: "1.0.0" })
namespace Hortinis.ContractProbe {
  @TypeSpec.JsonSchema.jsonSchema("https://schemas.hortinis.invalid/contract-emitter-probe/")
  namespace Models {
    @TypeSpec.doc("A synthetic model used to verify contract-emitter behavior.")
    model CompatibilityProbe {
      @TypeSpec.minLength(1)
      id: string;

      optionalValue?: string;
      nullableValue: string | null;
      sequence: string;
      kind: "ready" | "rejected";
    }
  }

  @route("/contract-probe")
  interface ProbeOperations {
    @get
    @operationId("readContractProbe")
    read(): Models.CompatibilityProbe;
  }
}
