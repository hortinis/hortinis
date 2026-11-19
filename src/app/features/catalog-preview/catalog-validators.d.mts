declare const catalogContract: {
  readonly consumerVersion: string;
  readonly fingerprints: Readonly<Record<string, string>>;
  readonly validators: Readonly<Record<string, (value: unknown) => boolean>>;
};

export default catalogContract;
