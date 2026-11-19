import { JsonPipe, NgTemplateOutlet } from '@angular/common';
import { Component, OnDestroy, computed, inject, resource, signal } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { CatalogPreviewService } from './catalog-preview-service';
import { CatalogImportError } from './catalog-record';

@Component({
  imports: [JsonPipe, MatButton, NgTemplateOutlet],
  selector: 'hortinis-catalog-preview',
  templateUrl: './catalog-preview.html',
  styleUrl: './catalog-preview.scss',
})
export class CatalogPreview implements OnDestroy {
  private readonly service = inject(CatalogPreviewService);
  private readonly controller = signal<AbortController | undefined>(undefined);
  protected readonly importing = computed(() => this.controller() !== undefined);
  protected readonly busy = signal(false);
  protected readonly message = signal('');
  protected readonly failure = signal('');
  protected readonly selectedId = signal('');
  protected readonly catalog = resource({ loader: () => this.service.browse() });
  protected readonly selected = computed(() =>
    this.catalog.value()?.subjects.find(({ id }) => id === this.selectedId()),
  );
  protected readonly details = resource({
    params: () => {
      const catalog = this.catalog.value();
      const subject = this.selected();
      return catalog && subject
        ? { snapshotId: catalog.active.id, subjectId: subject.id }
        : undefined;
    },
    loader: ({ params }) => this.service.subject(params.snapshotId, params.subjectId),
  });

  ngOnDestroy(): void {
    this.controller()?.abort();
  }

  protected async import(input: HTMLInputElement): Promise<void> {
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length || this.busy()) {
      return;
    }

    const controller = new AbortController();
    this.controller.set(controller);
    await this.run(async () => {
      await this.service.import(files, controller.signal);
      this.message.set('Catalog preview imported.');
    });
    this.controller.set(undefined);
  }

  protected cancel(): void {
    this.controller()?.abort();
  }

  protected async rollback(): Promise<void> {
    if (!this.busy()) {
      await this.run(async () => {
        await this.service.rollback();
        this.message.set('Previous catalog preview restored.');
      });
    }
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.failure.set('');
    this.message.set('');
    try {
      await action();
      this.selectedId.set('');
    } catch (error) {
      const name = (error as { name?: string } | undefined)?.name;
      if (name === 'AbortError') {
        this.message.set('Catalog import cancelled.');
      } else if (error instanceof CatalogImportError) {
        this.failure.set(error.message);
      } else if (
        name === 'QuotaExceededError' ||
        (error as { inner?: { name?: string } } | undefined)?.inner?.name === 'QuotaExceededError'
      ) {
        this.failure.set('The browser has insufficient storage for this catalog preview.');
      } else {
        this.failure.set('The catalog operation could not be completed.');
      }
    } finally {
      this.busy.set(false);
      this.catalog.reload();
    }
  }
}
