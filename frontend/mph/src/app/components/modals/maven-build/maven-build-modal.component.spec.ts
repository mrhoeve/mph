import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { MavenBuildService, BuildStatus, ProjectProgress } from '../../../services/maven-build.service';
import { MavenProjectService, ProjectAnalysis } from '../../../services/maven-project-service';
import { MavenBuildModalComponent } from './maven-build-modal.component';

function project(artifactId: string, path: string): ProjectAnalysis {
  return { groupId: 'org.example', artifactId, version: '1.0', path, modules: [], usages: [], hasSpringBootParent: false,
    canManageComponentVersions: false, managedProperties: [], isRoot: true, canScanNexusIq: false, buildStep: 0, dependsOn: [] };
}

describe('MavenBuildModalComponent', () => {
  let fixture: ComponentFixture<MavenBuildModalComponent>;
  let events: Subject<ProjectProgress>;
  const projectService = { getBuildOrder: vi.fn() };
  const buildService = { getBuildEvents: vi.fn(), startBuild: vi.fn(), stopBuild: vi.fn() };

  beforeEach(async () => {
    events = new Subject<ProjectProgress>();
    projectService.getBuildOrder.mockReset().mockReturnValue(of([project('alpha', '/alpha'), project('beta', '/beta')]));
    buildService.getBuildEvents.mockReset().mockReturnValue(events);
    buildService.startBuild.mockReset().mockReturnValue(of(undefined));
    buildService.stopBuild.mockReset().mockReturnValue(of(undefined));
    await TestBed.configureTestingModule({
      imports: [MavenBuildModalComponent],
      providers: [
        { provide: MavenProjectService, useValue: projectService },
        { provide: MavenBuildService, useValue: buildService }
      ]
    }).overrideComponent(MavenBuildModalComponent, { set: { template: '' } }).compileComponents();
    fixture = TestBed.createComponent(MavenBuildModalComponent);
    fixture.detectChanges();
  });

  it('loads projects selected and pending in build order', () => {
    expect(fixture.componentInstance.projects().map(item => ({ id: item.project.artifactId, selected: item.selected, status: item.status })))
      .toEqual([
        { id: 'alpha', selected: true, status: BuildStatus.PENDING },
        { id: 'beta', selected: true, status: BuildStatus.PENDING }
      ]);
    expect(fixture.componentInstance.isLoading()).toBe(false);
    expect(fixture.componentInstance.isAllSelected()).toBe(true);
  });

  it('starts only selected projects with the configured options', () => {
    fixture.componentInstance.projects.update(items => items.map(item => ({ ...item, selected: item.project.path === '/beta' })));
    fixture.componentInstance.skipUTs.set(false);
    fixture.componentInstance.maxParallel.set(4);

    fixture.componentInstance.startBuild();

    expect(buildService.startBuild).toHaveBeenCalledWith(['/beta'], { skipUTs: false, skipITs: true, parallel: true, maxParallel: 4 });
    expect(fixture.componentInstance.selectedProjectPath()).toBe('/beta');
    expect(fixture.componentInstance.projects().map(item => item.status)).toEqual([BuildStatus.SKIPPED, BuildStatus.PENDING]);
  });

  it('tracks status and bounded logs until all selected builds finish', () => {
    fixture.componentInstance.startBuild();
    events.next({ projectPath: '/alpha', artifactId: 'alpha', status: BuildStatus.SUCCESS, logLine: 'alpha complete' });
    events.next({ projectPath: '/beta', artifactId: 'beta', status: BuildStatus.FAILED, logLine: 'beta failed' });

    expect(fixture.componentInstance.projects().map(item => item.status)).toEqual([BuildStatus.SUCCESS, BuildStatus.FAILED]);
    fixture.componentInstance.selectProject(fixture.componentInstance.projects()[1]);
    expect(fixture.componentInstance.getSelectedProjectLogs().map(line => line.text)).toEqual(['beta failed']);
    expect(fixture.componentInstance.isBuilding()).toBe(false);
    expect(fixture.componentInstance.groupedProjects().map(group => group.name)).toEqual(['Failed', 'Completed']);
  });

  it('reports start failures and restores the controls', () => {
    buildService.startBuild.mockReturnValue(throwError(() => new Error('rejected')));
    fixture.componentInstance.startBuild();
    expect(fixture.componentInstance.errorMessage()).toBe('Failed to start build.');
    expect(fixture.componentInstance.isBuilding()).toBe(false);
  });
});
