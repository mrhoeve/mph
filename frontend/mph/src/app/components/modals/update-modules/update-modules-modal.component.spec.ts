import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { MavenProjectService, ProjectAnalysis } from '../../../services/maven-project-service';
import { UpdateModulesModalComponent } from './update-modules-modal.component';

function project(): ProjectAnalysis {
  return {
    groupId: 'org.example', artifactId: 'sample', version: '1.0', path: '/sample/pom.xml', modules: [], usages: [],
    hasSpringBootParent: false, canManageComponentVersions: true, managedProperties: [], isRoot: false,
    canScanNexusIq: false, buildStep: 0, dependsOn: []
  };
}

describe('UpdateModulesModalComponent', () => {
  const getLatestTag = vi.fn();
  let fixture: ComponentFixture<UpdateModulesModalComponent>;

  beforeEach(async () => {
    getLatestTag.mockReset();
    await TestBed.configureTestingModule({
      imports: [UpdateModulesModalComponent],
      providers: [{ provide: MavenProjectService, useValue: { getLatestTag } }]
    }).compileComponents();
    fixture = TestBed.createComponent(UpdateModulesModalComponent);
    fixture.componentRef.setInput('project', project());
  });

  it('loads latest tag and selects its version', () => {
    getLatestTag.mockReturnValue(of({ version: '1.2.3', tagName: 'v1.2.3' }));
    fixture.detectChanges();

    expect(getLatestTag).toHaveBeenCalledWith('/sample/pom.xml');
    expect(fixture.componentInstance.specifiedVersion()).toBe('1.2.3');
    expect(fixture.componentInstance.project.latestTagInfo).toEqual({ version: '1.2.3', tagName: 'v1.2.3' });
    expect(fixture.componentInstance.isLoadingTag()).toBe(false);
  });

  it('emits current or specified version according to selection', () => {
    getLatestTag.mockReturnValue(of(null));
    fixture.detectChanges();
    const emitted: Array<{ path: string; version: string }> = [];
    fixture.componentInstance.execute.subscribe(value => emitted.push(value));

    fixture.componentInstance.onExecute();
    fixture.componentInstance.selectedOption.set('specified');
    fixture.componentInstance.specifiedVersion.set('2.0');
    fixture.componentInstance.onExecute();

    expect(emitted).toEqual([
      { path: '/sample/pom.xml', version: '1.0' },
      { path: '/sample/pom.xml', version: '2.0' }
    ]);
  });

  it('clears loading state when tag lookup fails', () => {
    getLatestTag.mockReturnValue(throwError(() => new Error('failed')));
    fixture.detectChanges();

    expect(fixture.componentInstance.isLoadingTag()).toBe(false);
    expect(fixture.componentInstance.specifiedVersion()).toBe('');
  });
});
