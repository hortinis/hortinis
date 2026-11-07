import { inject, Service } from '@angular/core';
import { GardenRecord } from '../../../persistence/features/garden-record';
import { CreateGarden } from './create-garden';
import { MatDialog } from '@angular/material/dialog';
import { map } from 'rxjs/operators';
import { firstValueFrom } from 'rxjs';

@Service()
export class CreateGardenHandler {
  private readonly dialog = inject(MatDialog);

  public async createGarden(initial: boolean): Promise<GardenRecord | undefined> {
    const dialogRef = this.dialog.open(CreateGarden, {
      panelClass: 'hortinis-dialog',
      data: {
        initialConfiguration: initial,
      },
    });

    return await firstValueFrom(
      dialogRef.afterClosed().pipe(
        map(async (result) => {
          console.log('The dialog was closed');
          if (result?.type === 'success') {
            return result.garden as GardenRecord;
          }
          return undefined;
        }),
      ),
    );
  }
}
