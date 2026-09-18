import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Diary } from './diary';

describe('Diary', () => {
  let component: Diary;
  let fixture: ComponentFixture<Diary>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Diary],
    }).compileComponents();

    fixture = TestBed.createComponent(Diary);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
