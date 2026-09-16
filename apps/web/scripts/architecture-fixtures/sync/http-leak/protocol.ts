import { HttpClient } from '@angular/common/http';

export function illegalHttpDependency(): typeof HttpClient {
  return HttpClient;
}
