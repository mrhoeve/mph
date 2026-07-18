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

  it('handles loading failures and selection controls', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    projectService.getBuildOrder.mockReturnValueOnce(throwError(() => new Error('unavailable')));
    fixture.componentInstance.loadProjects();
    expect(fixture.componentInstance.errorMessage()).toBe('Failed to load projects.');
    expect(fixture.componentInstance.isLoading()).toBe(false);

    fixture.componentInstance.toggleSelectAll({ target: { checked: false } } as unknown as Event);
    expect(fixture.componentInstance.isAllSelected()).toBe(false);
    fixture.componentInstance.startBuild();
    expect(buildService.startBuild).not.toHaveBeenCalled();
    fixture.componentInstance.toggleSelectAll({ target: { checked: true } } as unknown as Event);
    expect(fixture.componentInstance.isAllSelected()).toBe(true);
  });

  it('groups running pending failed completed and skipped builds', () => {
    fixture.componentInstance.projects.update(items => [
      { ...items[0], status: BuildStatus.RUNNING },
      { ...items[1], status: BuildStatus.PENDING },
      { ...items[0], project: project('failed', '/failed'), status: BuildStatus.FAILED, originalIndex: 2 },
      { ...items[0], project: project('completed', '/completed'), status: BuildStatus.SUCCESS, originalIndex: 3 },
      { ...items[0], project: project('skipped', '/skipped'), status: BuildStatus.SKIPPED, originalIndex: 4 }
    ]);
    fixture.componentInstance.isBuilding.set(true);

    expect(fixture.componentInstance.groupedProjects().map(group => group.name))
      .toEqual(['Failed', 'Running', 'Pending', 'Completed']);
  });

  it('records bounded logs and handles event stream failures', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    fixture.componentInstance.startBuild();
    for (let index = 0; index < 1002; index++) {
      fixture.componentInstance.handleBuildEvent({
        projectPath: '/alpha', artifactId: 'alpha', status: BuildStatus.RUNNING, logLine: `line-${index}`
      });
    }
    expect(fixture.componentInstance.projectLogs()['/alpha']).toHaveLength(1000);
    expect(fixture.componentInstance.projectLogs()['/alpha'][0].text).toBe('line-2');

    events.error(new Error('stream failed'));
    expect(console.error).toHaveBeenCalledWith('SSE Error:', expect.any(Error));
  });

  it('updates options and log-following state from native controls', () => {
    const component = fixture.componentInstance as any;
    component.setSkipUTs({ target: { checked: false } } as unknown as Event);
    component.setSkipITs({ target: { checked: false } } as unknown as Event);
    component.setParallel({ target: { checked: false } } as unknown as Event);
    component.setMaxParallel({ target: { value: '3' } } as unknown as Event);
    component.setMaxParallel({ target: { value: 'invalid' } } as unknown as Event);
    component.toggleOptions();

    expect(component.skipUTs()).toBe(false);
    expect(component.skipITs()).toBe(false);
    expect(component.parallel()).toBe(false);
    expect(component.maxParallel()).toBe(3);
    expect(component.showOptions()).toBe(false);
    expect(component.getSelectedProjectLogs()).toEqual([]);

    const element = { scrollTop: 60, scrollHeight: 100, clientHeight: 20 };
    component.logContainer = { nativeElement: element };
    component.lastScrollTop = 60;
    component.isAutoScrolling = false;
    element.scrollTop = 20;
    component.onLogScroll();
    expect(component.followLog()).toBe(false);
    element.scrollTop = 80;
    component.onLogScroll();
    expect(component.followLog()).toBe(true);

    component.setFollowLog({ target: { checked: false } } as unknown as Event);
    expect(component.followLog()).toBe(false);
    component.setFollowLog({ target: { checked: true } } as unknown as Event);
    expect(component.followLog()).toBe(true);
    expect(element.scrollTop).toBe(100);
  });

  it('selects logs, stops builds and cleans up subscriptions', () => {
    vi.useFakeTimers();
    const component = fixture.componentInstance as any;
    const element = { scrollTop: 0, scrollHeight: 120, clientHeight: 20 };
    component.logContainer = { nativeElement: element };
    component.selectProject(component.projects()[0]);
    expect(component.selectedProjectPath()).toBe('/alpha');
    expect(element.scrollTop).toBe(120);
    vi.runAllTimers();
    expect(component.isAutoScrolling).toBe(false);

    component.stopBuild();
    expect(buildService.stopBuild).toHaveBeenCalled();
    expect(component.isBuilding()).toBe(false);
    expect(component.showOptions()).toBe(true);
    component.ngOnDestroy();
    expect(buildService.stopBuild).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });
});
