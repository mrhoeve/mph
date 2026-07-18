import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ProjectAnalysis } from '../../services/maven-project-service';
import { ProjectStateService } from '../../services/project-state-service';
import { ProjectListComponent } from './project-list.component';

function project(path: string, artifactId: string, modules: ProjectAnalysis[] = []): ProjectAnalysis {
  return {
    groupId: 'org.example', artifactId, version: '1.0', path, modules, usages: [], hasSpringBootParent: false,
    canManageComponentVersions: modules.length === 0, managedProperties: [], isRoot: modules.length > 0,
    canScanNexusIq: false, buildStep: 0, dependsOn: []
  };
}

describe('ProjectListComponent', () => {
  const child = project('/root/child', 'child');
  const root = project('/root', 'root', [child]);
  const selectedProject = signal<ProjectAnalysis | null>(null);
  const selectedRootProjects = signal(new Set<string>());
  const toggleProjectSelection = vi.fn();
  let fixture: ComponentFixture<ProjectListComponent>;

  beforeEach(async () => {
    selectedProject.set(null);
    selectedRootProjects.set(new Set());
    toggleProjectSelection.mockReset();
    await TestBed.configureTestingModule({
      imports: [ProjectListComponent],
      providers: [{
        provide: ProjectStateService,
        useValue: {
          projects: signal([root]), selectedProject, selectedRootProjects,
          isAllSelected: signal(false), isBuildOrderModalOpen: signal(false),
          toggleSelectAll: vi.fn(), toggleProjectSelection
        }
      }]
    }).compileComponents();
    fixture = TestBed.createComponent(ProjectListComponent);
    fixture.detectChanges();
  });

  it('selects projects and delegates checkbox selection', () => {
    fixture.componentInstance.selectProject(root);
    fixture.componentInstance.toggleProjectSelection(root);

    expect(selectedProject()).toEqual(root);
    expect(toggleProjectSelection).toHaveBeenCalledWith('/root');
    expect(fixture.componentInstance.isProjectSelected(root)).toBe(false);
    selectedRootProjects.set(new Set(['/root']));
    expect(fixture.componentInstance.isProjectSelected(root)).toBe(true);
  });

  it('expands and collapses module hierarchy without selecting project', () => {
    const event = new Event('click');
    const stop = vi.spyOn(event, 'stopPropagation');

    fixture.componentInstance.toggleExpand(root, event);
    fixture.detectChanges();
    expect(fixture.componentInstance.isExpanded(root)).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('child');

    fixture.componentInstance.toggleExpand(root, event);
    fixture.detectChanges();
    expect(fixture.componentInstance.isExpanded(root)).toBe(false);
    expect(stop).toHaveBeenCalledTimes(2);
  });

  it('enables and emits the rebase action only for selected roots', () => {
    const emitted = vi.fn();
    fixture.componentInstance.rebaseDevelop.subscribe(emitted);
    const button = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(candidate => candidate.textContent?.includes('Rebase on develop')) as HTMLButtonElement;

    expect(button.disabled).toBe(true);
    selectedRootProjects.set(new Set(['/root']));
    fixture.detectChanges();
    expect(button.disabled).toBe(false);
    button.click();
    expect(emitted).toHaveBeenCalledOnce();
  });
});
