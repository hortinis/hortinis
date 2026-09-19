import { BreakpointObserver, Breakpoints, BreakpointState } from '@angular/cdk/layout';
import { inject, Service } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map, OperatorFunction } from 'rxjs';

@Service()
export class Device {
  private readonly bp = inject(BreakpointObserver);
  private matches: OperatorFunction<BreakpointState, boolean> = map((state) => state.matches);

  public readonly mobile = toSignal(
    this.bp.observe([Breakpoints.Handset, Breakpoints.Tablet]).pipe(this.matches),
  );
  public readonly landscape = toSignal(
    this.bp
      .observe([Breakpoints.Web, Breakpoints.HandsetLandscape, Breakpoints.TabletLandscape])
      .pipe(this.matches),
  );
}
