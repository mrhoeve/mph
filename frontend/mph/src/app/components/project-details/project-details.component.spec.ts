import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProjectAnalysis } from '../../services/maven-project-service';
import { ProjectStateService } from '../../services/project-state-service';
import { ProjectDetailsComponent } from './project-details.component';

function project(overrides: Partial<ProjectAnalysis> = {}): ProjectAnalysis {
  return {
    groupId: 'org.example', artifactId: 'sample-module', version: '1.0.0', path: '/sample/pom.xml',
    modules: [], usages: [], hasSpringBootParent: false, canManageComponentVersions: true,
    managedProperties: [], isRoot: false, canScanNexusIq: false, buildStep: 0, dependsOn: [],
    ...overrides
  };
}

describe('ProjectDetailsComponent', () => {
  const selectedProject = signal<ProjectAnalysis | null>(null);
  let fixture: ComponentFixture<ProjectDetailsComponent>;

  beforeEach(async () => {
    selectedProject.set(null);
    await TestBed.configureTestingModule({
      imports: [ProjectDetailsComponent],
      providers: [{
        provide: ProjectStateService,
        useValue: {
          selectedProject,
          isScanning: signal(false),
          scanningMessage: signal('Scanning'),
          selectedProjectModuleUsages: signal<ProjectAnalysis[]>([])
        }
      }]
    }).compileComponents();
    fixture = TestBed.createComponent(ProjectDetailsComponent);
  });

  it('shows generic component management for a non Spring module', () => {
    selectedProject.set(project());
    fixture.detectChanges();
    const buttons = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].map(button => button.textContent?.trim());

    expect(buttons).toContain('⚙️ Manage component versions');
    expect(buttons).not.toContain('🍃 Upgrade Spring Boot');
  });

  it('retains Spring management and upgrade actions for Spring projects', () => {
    selectedProject.set(project({ hasSpringBootParent: true, springBootVersion: '4.1.0', isRoot: true }));
    fixture.detectChanges();
    const buttons = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].map(button => button.textContent?.trim());

    expect(buttons).toContain('⚙️ Manage spring component versions');
    expect(buttons).toContain('🍃 Upgrade Spring Boot');
    expect((fixture.nativeElement as HTMLElement).querySelector('.spring-badge')?.textContent?.trim()).toBe('🍃 4.1.0');
  });

  it('emits selected project when management action is clicked', () => {
    const selected = project();
    selectedProject.set(selected);
    const emitted: ProjectAnalysis[] = [];
    fixture.componentInstance.manageProperties.subscribe(value => emitted.push(value));
    fixture.detectChanges();

    const button = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')]
      .find(candidate => candidate.textContent?.includes('Manage component versions'));
    button?.click();

    expect(emitted).toEqual([selected]);
  });

  it('switches to SBOM tab', () => {
    selectedProject.set(project());
    fixture.detectChanges();
    const sbomTab = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.tab-button')]
      .find(candidate => candidate.textContent?.trim() === 'SBOM');

    sbomTab?.dispatchEvent(new Event('click'));
    fixture.detectChanges();

    expect(sbomTab?.classList.contains('active')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).querySelector('app-sbom-view')).not.toBeNull();
  });
});
