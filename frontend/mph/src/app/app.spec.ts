import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { App } from './app';
import { FileSystemService, FolderResponse } from './services/file-system-service';
import { SystemService } from './services/system-service';

describe('App', () => {
  const folder: FolderResponse = {
    path: '/projects',
    parentPath: '/',
    remembered: false,
    maxScanDepth: 3,
    children: []
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        {
          provide: FileSystemService,
          useValue: {
            current: vi.fn(() => of(folder)),
            folders: vi.fn(),
            saveBase: vi.fn()
          }
        },
        {
          provide: SystemService,
          useValue: {
            getInfo: vi.fn(() => of({ name: 'Maven Project Helper', version: 'Development' }))
          }
        }
      ]
    }).compileComponents();
  });

  it('creates the application', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    expect(fixture.componentInstance).toBeDefined();
  });

  it('renders the product title and runtime version from the backend', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('h1')?.textContent?.trim()).toBe('Maven Project Helper');
    expect(compiled.querySelector('.app-version')?.textContent?.trim()).toBe('Development');
  });

  it('shows folder selection when no folder is remembered', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('.scan-card h2')?.textContent?.trim()).toBe('Select a folder');
  });
});
