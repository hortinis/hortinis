import { BreakpointObserver, Breakpoints, BreakpointState } from '@angular/cdk/layout';
import { inject, Service } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { map, OperatorFunction } from 'rxjs';

@Service()
export class Device {
  private readonly bp = inject(BreakpointObserver);
  private matches: OperatorFunction<BreakpointState, boolean> = map((state) => state.matches);

  public readonly mobilePortrait = toSignal(
    this.bp.observe([Breakpoints.HandsetPortrait, Breakpoints.TabletPortrait]).pipe(this.matches),
  );
  public readonly mobileLandscape = toSignal(
    this.bp.observe([Breakpoints.HandsetLandscape, Breakpoints.TabletLandscape]).pipe(this.matches),
  );
}
