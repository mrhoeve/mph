import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { FileSystemService, FolderResponse } from '../../services/file-system-service';
import { ProjectStateService } from '../../services/project-state-service';
import { FolderSelector } from './folder-selector';

const current: FolderResponse = {
  path: '/projects', parentPath: '/', remembered: false, maxScanDepth: 4,
  nexusIqUrl: 'https://iq.example.org', nexusIqUser: 'api-user',
  nexusIqAppIdPrefix: 'pre-', nexusIqAppIdSuffix: '-suffix',
  children: [{ name: 'child', path: '/projects/child' }]
};

describe('FolderSelector', () => {
  const currentRequest = vi.fn();
  const folderRequest = vi.fn();
  const saveBase = vi.fn();
  const clearError = vi.fn();
  const setError = vi.fn();
  let fixture: ComponentFixture<FolderSelector>;

  beforeEach(async () => {
    currentRequest.mockReset().mockReturnValue(of(current));
    folderRequest.mockReset().mockReturnValue(of({ ...current, path: '/projects/child' }));
    saveBase.mockReset().mockReturnValue(of({ ...current, remembered: true }));
    clearError.mockReset();
    setError.mockReset();
    await TestBed.configureTestingModule({
      imports: [FolderSelector],
      providers: [
        { provide: FileSystemService, useValue: { current: currentRequest, folders: folderRequest, saveBase } },
        { provide: ProjectStateService, useValue: { clearError, setError } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(FolderSelector);
    fixture.detectChanges();
  });

  it('loads and renders current folder settings', () => {
    const element = fixture.nativeElement as HTMLElement;
    expect(currentRequest).toHaveBeenCalledTimes(1);
    expect(element.querySelector('.current-path strong')?.textContent?.trim()).toBe('/projects');
    expect((element.querySelector('#maxScanDepth') as HTMLInputElement).value).toBe('4');
    expect((element.querySelector('#nexusIqUrl') as HTMLInputElement).value).toBe('https://iq.example.org');
    expect(element.textContent).toContain('child');
  });

  it('opens a selected child folder', () => {
    const childButton = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.folder-list button')]
      .find(button => button.textContent?.includes('child'));
    childButton?.click();
    fixture.detectChanges();

    expect(folderRequest).toHaveBeenCalledWith('/projects/child');
    expect((fixture.nativeElement as HTMLElement).querySelector('.current-path strong')?.textContent?.trim()).toBe('/projects/child');
  });

  it('saves all settings and emits selected path', () => {
    const emitted: string[] = [];
    fixture.componentInstance.folderSelected.subscribe(value => emitted.push(value));
    const useButton = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('Use this folder'));
    useButton?.click();

    expect(saveBase).toHaveBeenCalledWith('/projects', 4, 'https://iq.example.org', 'api-user', undefined, 'pre-', '-suffix');
    expect(emitted).toEqual(['/projects']);
  });

  it('sends a newly entered Nexus IQ secret', () => {
    const passwordInput = (fixture.nativeElement as HTMLElement).querySelector('#nexusIqPass') as HTMLInputElement;
    passwordInput.value = 'replacement-secret';
    passwordInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const useButton = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')]
      .find(button => button.textContent?.includes('Use this folder'));
    useButton?.click();

    expect(saveBase).toHaveBeenCalledWith(
      '/projects', 4, 'https://iq.example.org', 'api-user', 'replacement-secret', 'pre-', '-suffix'
    );
  });

  it('reports current-folder loading failure', async () => {
    currentRequest.mockReturnValue(throwError(() => new Error('failed')));
    const failedFixture = TestBed.createComponent(FolderSelector);
    failedFixture.detectChanges();

    expect(setError).toHaveBeenCalledWith('Could not load the selected folder.');
  });
});
