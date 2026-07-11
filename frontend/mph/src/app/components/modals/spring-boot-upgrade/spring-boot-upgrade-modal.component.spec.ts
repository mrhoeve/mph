import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { ProjectAnalysis } from '../../../services/maven-project-service';
import { SpringBootDiscoveryService } from '../../../services/spring-boot-discovery.service';
import { SpringBootUpgradeModalComponent } from './spring-boot-upgrade-modal.component';

const project: ProjectAnalysis = {
  groupId: 'org.example', artifactId: 'sample', version: '1.0', path: '/sample/pom.xml', modules: [], usages: [],
  hasSpringBootParent: true, canManageComponentVersions: true, springBootVersion: '3.4.1', managedProperties: [],
  isRoot: true, canScanNexusIq: false, buildStep: 0, dependsOn: []
};

describe('SpringBootUpgradeModalComponent', () => {
  const getVersionsFromInitializr = vi.fn();
  const getSuggestions = vi.fn();
  let fixture: ComponentFixture<SpringBootUpgradeModalComponent>;

  beforeEach(async () => {
    getVersionsFromInitializr.mockReset();
    getSuggestions.mockReset();
    await TestBed.configureTestingModule({
      imports: [SpringBootUpgradeModalComponent],
      providers: [{ provide: SpringBootDiscoveryService, useValue: { getVersionsFromInitializr, getSuggestions } }]
    }).compileComponents();
    fixture = TestBed.createComponent(SpringBootUpgradeModalComponent);
    fixture.componentRef.setInput('project', project);
  });

  it('loads and stores upgrade suggestions', () => {
    const suggestions = { currentVersion: '3.4.1', latestInSeries: '3.4.3', latestOverall: '3.5.1' };
    getVersionsFromInitializr.mockReturnValue(of(['3.4.3', '3.5.1']));
    getSuggestions.mockReturnValue(suggestions);

    fixture.detectChanges();

    expect(getSuggestions).toHaveBeenCalledWith('3.4.1', ['3.4.3', '3.5.1']);
    expect(fixture.componentInstance.suggestions()).toEqual(suggestions);
    expect(fixture.componentInstance.isLoading()).toBe(false);
  });

  it('reports empty and failed version discovery distinctly', () => {
    getVersionsFromInitializr.mockReturnValue(of([]));
    fixture.detectChanges();
    expect(fixture.componentInstance.errorMessage()).toBe('Failed to retrieve Spring Boot versions.');

    const failedFixture = TestBed.createComponent(SpringBootUpgradeModalComponent);
    failedFixture.componentRef.setInput('project', project);
    getVersionsFromInitializr.mockReturnValue(throwError(() => new Error('failed')));
    failedFixture.detectChanges();
    expect(failedFixture.componentInstance.errorMessage()).toBe('Failed to load Spring Boot version suggestions.');
  });

  it('emits selected upgrade version', () => {
    getVersionsFromInitializr.mockReturnValue(of([]));
    fixture.detectChanges();
    const emitted: string[] = [];
    fixture.componentInstance.upgrade.subscribe(value => emitted.push(value));

    fixture.componentInstance.onUpgrade('3.5.1');

    expect(emitted).toEqual(['3.5.1']);
  });
});
