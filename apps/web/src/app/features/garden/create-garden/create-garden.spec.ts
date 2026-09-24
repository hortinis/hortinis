import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CreateGarden } from './create-garden';

describe('CreateGarden', () => {
  let component: CreateGarden;
  let fixture: ComponentFixture<CreateGarden>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreateGarden],
    }).compileComponents();

    fixture = TestBed.createComponent(CreateGarden);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
