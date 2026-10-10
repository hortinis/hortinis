@TypeSpec.JsonSchema.jsonSchema
namespace Hortinis.Sync.Gardens {
  @doc("A garden name containing at least one non-whitespace character.")
  @minLength(1)
  @pattern("\\S")
  scalar GardenName extends string;

  @doc("The accepted current projection of a garden.")
  model Garden {
    gardenId: Models.Uuid;
    name: GardenName;
    revision: Models.PositiveDecimal;
  }

  @doc("Create a garden with a stable client-generated identifier.")
  model CreateGardenOperation {
    @doc("The stable identifier reused for every retry of this operation.")
    operationId: Models.Uuid;

    gardenId: Models.Uuid;
    kind: "createGarden";
    name: GardenName;
  }

  @doc("The stable result of accepting or identically replaying garden creation.")
  model GardenOperationResult {
    outcome: "accepted";
    operationId: Models.Uuid;
    record: Garden;
    sequence: Models.PositiveDecimal;
  }

  @doc("One accepted garden change in server-sequence order.")
  model GardenChange {
    operationId: Models.Uuid;
    record: Garden;
    sequence: Models.PositiveDecimal;
  }
}
