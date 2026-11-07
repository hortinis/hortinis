import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BaseConfiguration } from './base-configuration';

describe('Configuration', () => {
  let component: BaseConfiguration;
  let fixture: ComponentFixture<BaseConfiguration>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BaseConfiguration],
    }).compileComponents();

    fixture = TestBed.createComponent(BaseConfiguration);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
