export interface Garden {
  id: string;
  spaces: Space[];
  name?: string;
}

export interface Space {
  id: string;
  garden: Garden;
  name: string;
  type: string;
}
