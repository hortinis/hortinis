import { v7 as uuidv7 } from 'uuid';

declare const uuidBrand: unique symbol;

export type HortinisUuid = string & {
  readonly [uuidBrand]: true;
};

export function generateUuidV7(): HortinisUuid {
  return uuidv7() as HortinisUuid;
}
