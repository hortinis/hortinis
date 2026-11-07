import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CreateGarden } from './create-garden';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

describe('CreateGarden', () => {
  let component: CreateGarden;
  let fixture: ComponentFixture<CreateGarden>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreateGarden],
      providers: [
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { initialConfiguration: false } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CreateGarden);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
