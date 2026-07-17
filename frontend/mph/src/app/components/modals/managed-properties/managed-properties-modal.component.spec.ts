import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { ManagedProperty, MavenProjectService, ProjectAnalysis } from '../../../services/maven-project-service';
import { ManagedPropertiesModalComponent } from './managed-properties-modal.component';

const project: ProjectAnalysis = {
  groupId: 'org.example', artifactId: 'sample', version: '1.0', path: '/sample/pom.xml', modules: [], usages: [],
  hasSpringBootParent: false, canManageComponentVersions: true, managedProperties: [], isRoot: false,
  canScanNexusIq: false, buildStep: 0, dependsOn: []
};
const properties: ManagedProperty[] = [
  { name: 'alpha.version', value: '1.0', inheritedValue: null, source: 'Parent', isOverridden: false },
  { name: 'beta.version', value: '2.0', inheritedValue: '1.9', source: 'Local POM', isOverridden: true }
];

describe('ManagedPropertiesModalComponent', () => {
  const getManagedProperties = vi.fn();
  let fixture: ComponentFixture<ManagedPropertiesModalComponent>;

  beforeEach(async () => {
    getManagedProperties.mockReset();
    getManagedProperties.mockReturnValue(of(properties));
    await TestBed.configureTestingModule({
      imports: [ManagedPropertiesModalComponent],
      providers: [{ provide: MavenProjectService, useValue: { getManagedProperties } }]
    }).compileComponents();
    fixture = TestBed.createComponent(ManagedPropertiesModalComponent);
    fixture.componentRef.setInput('project', project);
    fixture.detectChanges();
  });

  it('loads managed properties for selected module', () => {
    expect(getManagedProperties).toHaveBeenCalledWith('/sample/pom.xml');
    expect(fixture.componentInstance.properties()).toEqual(properties);
    expect(fixture.componentInstance.isLoading()).toBe(false);
  });

  it('filters by name value and override state', () => {
    fixture.componentInstance.propertySearchQuery.set('ALPHA');
    expect(fixture.componentInstance.filteredProperties()).toEqual([properties[0]]);

    fixture.componentInstance.propertySearchQuery.set('2.0');
    expect(fixture.componentInstance.filteredProperties()).toEqual([properties[1]]);

    fixture.componentInstance.propertySearchQuery.set('');
    fixture.componentInstance.showOnlyOverrides.set(true);
    expect(fixture.componentInstance.filteredProperties()).toEqual([properties[1]]);
  });

  it('filters the rendered table when show only overrides is clicked', () => {
    const element: HTMLElement = fixture.nativeElement;
    const overrideToggle = element.querySelector<HTMLInputElement>('.filter-checkbox input');

    expect(overrideToggle).not.toBeNull();
    expect(element.querySelectorAll('tbody tr')).toHaveLength(2);

    overrideToggle!.click();
    fixture.detectChanges();

    const rows = element.querySelectorAll('tbody tr');
    expect(fixture.componentInstance.showOnlyOverrides()).toBe(true);
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('beta.version');
    expect(rows[0].textContent).not.toContain('alpha.version');
  });

  it('emits override and removal requests with exact property', () => {
    const overrides: Array<{ prop: ManagedProperty }> = [];
    const removals: ManagedProperty[] = [];
    fixture.componentInstance.override.subscribe(value => overrides.push(value));
    fixture.componentInstance.removeOverride.subscribe(value => removals.push(value));

    fixture.componentInstance.onOverride(properties[0]);
    fixture.componentInstance.onRemoveOverride(properties[1]);

    expect(overrides).toEqual([{ prop: properties[0] }]);
    expect(removals).toEqual([properties[1]]);
  });
});
