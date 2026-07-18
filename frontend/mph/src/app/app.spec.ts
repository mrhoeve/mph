import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { App } from './app';
import { FileSystemService, FolderResponse } from './services/file-system-service';
import { ManagedProperty, MavenProjectService, ProjectAnalysis } from './services/maven-project-service';
import { ProjectStateService } from './services/project-state-service';
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

function project(artifactId = 'sample'): ProjectAnalysis {
  return {
    groupId: 'org.example', artifactId, version: '1.0', path: `/${artifactId}`, modules: [], usages: [],
    hasSpringBootParent: false, canManageComponentVersions: true, managedProperties: [], isRoot: true,
    canScanNexusIq: true, buildStep: 0, dependsOn: []
  };
}

describe('App orchestration', () => {
  const current = vi.fn();
  const getInfo = vi.fn();
  const maven = {
    bulkUpdateVersion: vi.fn(), syncDevelop: vi.fn(), upgradeSpringBoot: vi.fn(), overrideProperty: vi.fn(),
    removePropertyOverride: vi.fn(), scanNexusIq: vi.fn()
  };
  const state = {
    selectedRootProjects: signal(new Set<string>()),
    scanningMessage: signal(''),
    isScanning: signal(false),
    selectedProject: signal<ProjectAnalysis | null>(null),
    projectForUpdateModules: signal<ProjectAnalysis | null>(null),
    isUpdateModulesModalOpen: signal(false),
    scan: vi.fn(), setError: vi.fn(), setInfo: vi.fn(), updateProjectsData: vi.fn()
  };

  beforeEach(async () => {
    current.mockReset().mockReturnValue(of({ path: '/projects', parentPath: '/', remembered: false, maxScanDepth: 3, children: [] }));
    getInfo.mockReset().mockReturnValue(of({ name: 'Maven Project Helper', version: 'Test' }));
    Object.values(maven).forEach(mock => mock.mockReset());
    maven.bulkUpdateVersion.mockReturnValue(of([project()]));
    maven.syncDevelop.mockReturnValue(of({ projects: [project()], messages: [] }));
    maven.upgradeSpringBoot.mockReturnValue(of([project()]));
    maven.overrideProperty.mockReturnValue(of([project()]));
    maven.removePropertyOverride.mockReturnValue(of([project()]));
    maven.scanNexusIq.mockReturnValue(of({ message: 'completed', summary: null }));
    state.selectedRootProjects.set(new Set());
    state.isScanning.set(false);
    state.selectedProject.set(null);
    state.projectForUpdateModules.set(null);
    state.isUpdateModulesModalOpen.set(false);
    state.scan.mockReset();
    state.setError.mockReset();
    state.setInfo.mockReset();
    state.updateProjectsData.mockReset();

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        { provide: FileSystemService, useValue: { current } },
        { provide: SystemService, useValue: { getInfo } },
        { provide: MavenProjectService, useValue: maven },
        { provide: ProjectStateService, useValue: state }
      ]
    }).overrideComponent(App, { set: { template: '' } }).compileComponents();
  });

  function createApp(): { fixture: ComponentFixture<App>; app: any } {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return { fixture, app: fixture.componentInstance as any };
  }

  it('loads remembered folders', () => {
    current.mockReturnValueOnce(of({ path: '/remembered', parentPath: '/', remembered: true, maxScanDepth: 3, children: [] }));
    const remembered = createApp().app;
    expect(remembered.selectedBasePath()).toBe('/remembered');
    expect(remembered.isSelectingFolder()).toBe(false);
    expect(state.scan).toHaveBeenCalledOnce();

  });

  it('reports folder and system information startup failures', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    current.mockReturnValueOnce(throwError(() => new Error('folder unavailable')));
    getInfo.mockReturnValueOnce(throwError(() => new Error('system unavailable')));

    const { app } = createApp();

    expect(state.setError).toHaveBeenCalledWith('Could not load the configured base folder.');
    expect(app.isSelectingFolder()).toBe(true);
    expect(app.isLoadingBaseFolder()).toBe(false);
    expect(console.error).toHaveBeenCalledWith('Failed to load system info', expect.any(Error));
  });

  it('updates folder and modal selection state', () => {
    const { app } = createApp();
    const selected = project('selected');
    const property: ManagedProperty = { name: 'library.version', value: '1.0', inheritedValue: null, source: 'Local POM', isOverridden: true };

    app.folderSelected('/new');
    expect(app.selectedBasePath()).toBe('/new');
    expect(state.scan).toHaveBeenCalledOnce();
    app.changeFolder();
    expect(app.isSelectingFolder()).toBe(true);

    app.updateToLatest(selected);
    app.updateAllModulesAndUsages(selected);
    expect(state.projectForUpdateModules()).toBe(selected);
    expect(state.isUpdateModulesModalOpen()).toBe(true);

    app.openOverrideModal({ prop: property });
    expect(app.overridePropertyData()).toBeNull();
    app.openVersionsModal(selected);
    app.openOverrideModal({ prop: property });
    expect(app.isVersionsModalOpen()).toBe(true);
    expect(app.overridePropertyData()).toEqual({ project: selected, prop: property });
  });

  it('handles bulk updates on success and failure', () => {
    const { app } = createApp();
    state.selectedRootProjects.set(new Set(['/one']));
    app.executeBulkUpdate({ paths: ['/one'], prefix: 'DEV-', updateDependents: true, mode: 'ADD_PREFIX', branchName: 'test/branch' });
    expect(maven.bulkUpdateVersion).toHaveBeenCalledWith(['/one'], 'DEV-', true, 'ADD_PREFIX', 'test/branch', true);
    expect(state.updateProjectsData).toHaveBeenCalled();
    expect(state.selectedRootProjects().size).toBe(0);
    expect(state.isScanning()).toBe(false);

    maven.bulkUpdateVersion.mockReturnValueOnce(throwError(() => new Error('failed')));
    app.executeBulkUpdate({ paths: ['/one'], prefix: '', updateDependents: false, mode: 'CURRENT', branchName: '' });
    expect(state.setError).toHaveBeenCalledWith('Bulk update failed.');
    expect(state.isScanning()).toBe(false);
  });

  it('syncs develop with messages and exposes backend errors', () => {
    const { app } = createApp();
    app.executeSyncDevelop();
    expect(maven.syncDevelop).not.toHaveBeenCalled();

    state.selectedRootProjects.set(new Set(['/one']));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    maven.syncDevelop.mockReturnValueOnce(of({ projects: [project()], messages: ['Updated test branch'] }));
    app.executeSyncDevelop();
    expect(maven.syncDevelop).toHaveBeenCalledWith(['/one'], true);
    expect(state.setInfo).toHaveBeenCalledWith('Updated test branch');
    expect(state.selectedRootProjects().size).toBe(0);

    state.selectedRootProjects.set(new Set(['/one']));
    maven.syncDevelop.mockReturnValueOnce(throwError(() => ({ error: { message: 'merge conflict' } })));
    app.executeSyncDevelop();
    expect(state.setError).toHaveBeenCalledWith('Sync develop failed: merge conflict');
  });

  it('updates current versions and manually selected module versions', () => {
    const { app } = createApp();
    app.executeVersionUpdate();
    expect(maven.bulkUpdateVersion).not.toHaveBeenCalled();

    state.selectedRootProjects.set(new Set(['/one', '/two']));
    app.executeVersionUpdate();
    expect(maven.bulkUpdateVersion).toHaveBeenCalledWith(['/one', '/two'], '', true, 'CURRENT', null, false);
    expect(state.setInfo).toHaveBeenCalledWith('Updated versions for 2 projects and their dependents.');

    maven.bulkUpdateVersion.mockReturnValueOnce(throwError(() => new Error('manual failed')));
    app.executeUpdateModulesAndUsages({ path: '/one', version: '2.0' });
    expect(state.setError).toHaveBeenCalledWith('Update failed: manual failed');

    maven.bulkUpdateVersion.mockReturnValueOnce(of([project('updated')]));
    app.executeUpdateModulesAndUsages({ path: '/one', version: '2.1' });
    expect(state.updateProjectsData).toHaveBeenCalledWith([project('updated')]);
  });

  it('guards and executes Spring Boot upgrades', () => {
    const { app } = createApp();
    app.upgradeSpringBoot('4.1.0');
    expect(maven.upgradeSpringBoot).not.toHaveBeenCalled();

    const selected = project('boot-app');
    state.selectedProject.set(selected);
    app.upgradeSpringBoot('4.1.0');
    expect(maven.upgradeSpringBoot).toHaveBeenCalledWith('/boot-app', '4.1.0');
    expect(state.updateProjectsData).toHaveBeenCalled();

    maven.upgradeSpringBoot.mockReturnValueOnce(throwError(() => new Error('upgrade failed')));
    app.upgradeSpringBoot('4.2.0');
    expect(state.setError).toHaveBeenCalledWith('Spring Boot upgrade failed.');
    app.upgradeSpringBoot('');
    expect(maven.upgradeSpringBoot).toHaveBeenCalledTimes(2);
  });

  it('overrides and removes managed properties with refreshes', () => {
    vi.useFakeTimers();
    const { app } = createApp();
    const selected = project('managed');
    const property: ManagedProperty = { name: 'library.version', value: '1.0', inheritedValue: null, source: 'Local POM', isOverridden: true };

    app.executeOverride({ newValue: '2.0', remark: 'Test fixture update' });
    expect(maven.overrideProperty).not.toHaveBeenCalled();
    app.openVersionsModal(selected);
    app.openOverrideModal({ prop: property });
    app.executeOverride({ newValue: '2.0', remark: 'Test fixture update' });
    expect(maven.overrideProperty).toHaveBeenCalledWith('/managed', 'library.version', '2.0', 'Test fixture update');
    vi.runAllTimers();
    expect(app.versionsModalProject()).toBe(selected);

    vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValue(true);
    app.removeOverride(property);
    expect(maven.removePropertyOverride).not.toHaveBeenCalled();
    app.removeOverride(property);
    expect(maven.removePropertyOverride).toHaveBeenCalledWith('/managed', 'library.version');
    vi.runAllTimers();
    expect(app.versionsModalProject()).toBe(selected);
    vi.useRealTimers();
  });

  it('reports property and Nexus IQ failures and stores successful scan reports', () => {
    const { app } = createApp();
    const selected = project('scanned');
    const property: ManagedProperty = { name: 'library.version', value: '1.0', inheritedValue: null, source: 'Local POM', isOverridden: true };
    app.openVersionsModal(selected);
    app.openOverrideModal({ prop: property });

    maven.overrideProperty.mockReturnValueOnce(throwError(() => new Error('failed')));
    app.executeOverride({ newValue: '2.0', remark: '' });
    expect(state.setError).toHaveBeenCalledWith('Property override failed.');

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    maven.removePropertyOverride.mockReturnValueOnce(throwError(() => new Error('failed')));
    app.removeOverride(property);
    expect(state.setError).toHaveBeenCalledWith('Failed to remove property override.');

    app.executeNexusIqScan(selected);
    expect(app.nexusIqReport()).toEqual({ result: { message: 'completed', summary: null }, projectName: 'scanned' });
    expect(state.scan).toHaveBeenCalled();
    expect(state.isScanning()).toBe(false);

    maven.scanNexusIq.mockReturnValueOnce(throwError(() => new Error('scan failed')));
    app.executeNexusIqScan(selected);
    expect(state.setError).toHaveBeenCalledWith('Failed to trigger Nexus IQ scan: scan failed');
  });
});
