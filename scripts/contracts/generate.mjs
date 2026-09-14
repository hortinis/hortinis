import { assertTypeSpecSuccess, compileTypeSpecProject, repositoryRoot } from "./tools.mjs";

const program = await compileTypeSpecProject(`${repositoryRoot}/contracts/typespec`);
assertTypeSpecSuccess(program, "The production contract");
