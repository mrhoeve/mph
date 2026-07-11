import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { MavenProjectService, ProjectAnalysis, SbomComponent } from '../../services/maven-project-service';
import { SbomViewComponent } from './sbom-view.component';

const child: SbomComponent = { groupId: 'org.example', artifactId: 'child', version: '2.0', type: 'library', scope: 'runtime', licenses: ['Apache-2.0'], dependencies: [] };
const root: SbomComponent = { groupId: 'org.example', artifactId: 'root', version: '1.0', type: 'library', scope: 'compile', licenses: [], dependencies: [child, child] };
const project: ProjectAnalysis = {
  groupId: 'org.example', artifactId: 'sample', version: '1.0', path: '/sample/pom.xml', modules: [], usages: [],
  hasSpringBootParent: false, canManageComponentVersions: false, managedProperties: [], isRoot: true,
  canScanNexusIq: false, buildStep: 0, dependsOn: []
};

describe('SbomViewComponent', () => {
  let fixture: ComponentFixture<SbomViewComponent>;
  const service = {
    getSbomDetails: vi.fn(),
    getSbomExportUrl: vi.fn()
  };

  beforeEach(async () => {
    service.getSbomDetails.mockReset().mockReturnValue(of({ components: [root], rawXml: '<bom/>', rawJson: '{"bom":true}' }));
    service.getSbomExportUrl.mockReset().mockReturnValue('/export?format=json');
    await TestBed.configureTestingModule({
      imports: [SbomViewComponent],
      providers: [{ provide: MavenProjectService, useValue: service }]
    }).compileComponents();
    fixture = TestBed.createComponent(SbomViewComponent);
    fixture.componentRef.setInput('project', project);
  });

  it('loads and renders unique dependency totals and raw formats', () => {
    fixture.detectChanges();
    expect(service.getSbomDetails).toHaveBeenCalledWith('/sample/pom.xml');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('2 components found');

    const buttons = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.toggle-btn')];
    buttons.find(button => button.textContent === 'JSON')?.dispatchEvent(new Event('click'));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.raw-content')?.textContent).toContain('{"bom":true}');
  });

  it('filters components using artifact, group, or version text', () => {
    fixture.detectChanges();
    const input = (fixture.nativeElement as HTMLElement).querySelector('.search-input') as HTMLInputElement;
    input.value = 'child';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr')];
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('child');
    expect(rows[0].textContent).toContain('2.0');
  });

  it('shows a useful failure and retries the request', () => {
    service.getSbomDetails.mockReturnValueOnce(throwError(() => new Error('unavailable')));
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Failed to load SBOM details');

    service.getSbomDetails.mockReturnValue(of({ components: [], rawXml: '', rawJson: '' }));
    const retry = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find(button => button.textContent?.trim() === 'Retry');
    retry?.click();
    fixture.detectChanges();
    expect(service.getSbomDetails).toHaveBeenCalledTimes(2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No dependencies found.');
  });

  it('opens the generated export URL in a new tab', () => {
    fixture.detectChanges();
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    fixture.componentInstance.export('json');
    expect(service.getSbomExportUrl).toHaveBeenCalledWith('/sample/pom.xml', 'json');
    expect(open).toHaveBeenCalledWith('/export?format=json', '_blank');
  });
});
