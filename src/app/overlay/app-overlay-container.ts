import { Injectable } from '@angular/core';
import { OverlayContainer } from '@angular/cdk/overlay';

/**
 * Keeps CDK overlays inside the application root so they inherit application
 * layout classes and styles.
 */
@Injectable()
export class AppOverlayContainer extends OverlayContainer {
  protected override _createContainer(): void {
    super._createContainer();

    const appRoot = this._document.querySelector('hortinis-root');
    const container = this._containerElement;
    if (appRoot && container && container.parentElement !== appRoot) {
      appRoot.appendChild(container);
    }
  }
}
