import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Layout } from './layout';
import { provideRouter } from '@angular/router';
import { MatIconTestingModule } from '@angular/material/icon/testing';

describe('Layout', () => {
  let component: Layout;
  let fixture: ComponentFixture<Layout>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Layout, MatIconTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(Layout);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
