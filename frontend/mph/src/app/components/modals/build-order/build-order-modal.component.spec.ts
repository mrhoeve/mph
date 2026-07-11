import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MavenProjectService, ProjectAnalysis } from '../../../services/maven-project-service';
import { BuildOrderModalComponent } from './build-order-modal.component';

function project(artifactId: string, path: string, overrides: Partial<ProjectAnalysis> = {}): ProjectAnalysis {
  return { groupId: 'org.example', artifactId, version: '1.0', path, modules: [], usages: [], hasSpringBootParent: false,
    canManageComponentVersions: false, managedProperties: [], isRoot: false, canScanNexusIq: false, buildStep: 0, dependsOn: [], ...overrides };
}

describe('BuildOrderModalComponent', () => {
  let fixture: ComponentFixture<BuildOrderModalComponent>;
  const service = { getBuildOrder: vi.fn(), getExcelUrl: vi.fn() };

  beforeEach(async () => {
    const childB = project('child-b', '/root/child-b');
    const childA = project('child-a', '/root/child-a');
    const root = project('root', '/root', { isRoot: true, modules: [childB, childA] });
    service.getBuildOrder.mockReset().mockReturnValue(of([root]));
    service.getExcelUrl.mockReset().mockReturnValue('/api/projects/build-order/excel');
    await TestBed.configureTestingModule({
      imports: [BuildOrderModalComponent],
      providers: [{ provide: MavenProjectService, useValue: service }]
    }).overrideComponent(BuildOrderModalComponent, { set: { template: '' } }).compileComponents();
    fixture = TestBed.createComponent(BuildOrderModalComponent);
    fixture.detectChanges();
  });

  it('loads roots and presents a sorted hierarchy with depth', () => {
    expect(fixture.componentInstance.isLoading()).toBe(false);
    expect(fixture.componentInstance.hierarchicalProjects().map(item => ({ id: item.project.artifactId, depth: item.depth, parent: item.isParent })))
      .toEqual([
        { id: 'root', depth: 0, parent: true },
        { id: 'child-a', depth: 1, parent: false },
        { id: 'child-b', depth: 1, parent: false }
      ]);
    expect(fixture.componentInstance.allProjectsFlattened().map(item => item.artifactId)).toEqual(['child-a', 'child-b', 'root']);
  });

  it('derives the focused name and indented display label', () => {
    fixture.componentInstance.selectedProjectPath.set('/root/child-a');
    expect(fixture.componentInstance.focusedProjectName()).toBe('child-a');
    expect(fixture.componentInstance.getProjectDisplayName({ project: project('module', '/module'), depth: 2 }))
      .toBe('\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0module');
  });

  it('opens the generated build-order spreadsheet', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    fixture.componentInstance.downloadExcel();
    expect(service.getExcelUrl).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledWith('/api/projects/build-order/excel', '_blank');
  });

  it('shows a stable error when build-order calculation fails', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    service.getBuildOrder.mockReturnValue(throwError(() => new Error('cycle')));
    fixture.componentInstance.loadBuildOrder();
    expect(fixture.componentInstance.isLoading()).toBe(false);
    expect(fixture.componentInstance.errorMessage()).toContain('Failed to calculate build order');
  });
});
