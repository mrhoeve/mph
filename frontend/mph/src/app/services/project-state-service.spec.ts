import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { MavenProjectService, ProjectAnalysis } from './maven-project-service';
import { ProjectStateService } from './project-state-service';

function project(path: string, artifactId: string, modules: ProjectAnalysis[] = [], usages: ProjectAnalysis['usages'] = []): ProjectAnalysis {
  return {
    groupId: 'org.example', artifactId, version: '1.0.0', path, modules, usages,
    hasSpringBootParent: false, canManageComponentVersions: modules.length === 0,
    managedProperties: [], isRoot: modules.length > 0, canScanNexusIq: false, buildStep: 0, dependsOn: []
  };
}

describe('ProjectStateService', () => {
  const analyze = vi.fn();
  let service: ProjectStateService;

  beforeEach(() => {
    analyze.mockReset();
    TestBed.configureTestingModule({
      providers: [{ provide: MavenProjectService, useValue: { analyze } }]
    });
    service = TestBed.inject(ProjectStateService);
  });

  it('toggles all root selections', () => {
    service.projects.set([project('/a', 'a'), project('/b', 'b')]);

    service.toggleSelectAll();
    expect([...service.selectedRootProjects()]).toEqual(['/a', '/b']);
    expect(service.isAllSelected()).toBe(true);

    service.toggleSelectAll();
    expect([...service.selectedRootProjects()]).toEqual([]);
    expect(service.isAllSelected()).toBe(false);
  });

  it('toggles a single project without mutating prior set', () => {
    const initial = new Set(['/a']);
    service.selectedRootProjects.set(initial);

    service.toggleProjectSelection('/b');
    expect([...service.selectedRootProjects()]).toEqual(['/a', '/b']);
    expect([...initial]).toEqual(['/a']);

    service.toggleProjectSelection('/a');
    expect([...service.selectedRootProjects()]).toEqual(['/b']);
  });

  it('finds nested modules and gathers only modules with usages', () => {
    const used = project('/root/used', 'used', [], [{
      usedInGroupId: 'org.example', usedInArtifactId: 'consumer', usedVersion: '1.0.0', path: '/consumer'
    }]);
    const deep = project('/root/container/deep', 'deep', [], [{
      usedInGroupId: 'org.example', usedInArtifactId: 'consumer-2', usedVersion: '1.0.0', path: '/consumer-2'
    }]);
    const root = project('/root', 'root', [used, project('/root/unused', 'unused'), project('/root/container', 'container', [deep])]);
    service.selectedProject.set(root);

    expect(service.findProjectByPath([root], '/root/container/deep')).toEqual(deep);
    expect(service.findProjectByPath([root], '/missing')).toBeNull();
    expect(service.selectedProjectModuleUsages().map(value => value.artifactId)).toEqual(['used', 'deep']);
  });

  it('updates selected project to corresponding refreshed instance', () => {
    const original = project('/a', 'old-name');
    const refreshed = project('/a', 'new-name');
    service.selectedProject.set(original);

    service.updateProjectsData([refreshed]);

    expect(service.projects()).toEqual([refreshed]);
    expect(service.selectedProject()).toEqual(refreshed);
  });

  it('scans and stores returned projects', () => {
    const projects = [project('/a', 'a')];
    analyze.mockReturnValue(of(projects));

    service.scan();

    expect(analyze).toHaveBeenCalledTimes(1);
    expect(service.projects()).toEqual(projects);
    expect(service.isScanning()).toBe(false);
    expect(service.errorMessage()).toBeNull();
  });

  it('reports scan errors and clears scanning state', () => {
    analyze.mockReturnValue(throwError(() => new Error('failed')));

    service.scan();

    expect(service.isScanning()).toBe(false);
    expect(service.errorMessage()).toBe('Scanning failed. Make sure the folder contains valid Maven projects.');
    service.clearError();
    expect(service.errorMessage()).toBeNull();
  });

  it('sets and clears informational messages', () => {
    service.setInfo('completed');
    expect(service.infoMessage()).toBe('completed');
    service.clearInfo();
    expect(service.infoMessage()).toBeNull();
  });
});
