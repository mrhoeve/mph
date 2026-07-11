import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ManagedProperty, ProjectAnalysis } from '../../../services/maven-project-service';
import { PropertyOverrideModalComponent } from './property-override-modal.component';

const project: ProjectAnalysis = {
  groupId: 'org.example', artifactId: 'sample', version: '1.0', path: '/sample/pom.xml', modules: [], usages: [],
  hasSpringBootParent: false, canManageComponentVersions: true, managedProperties: [], isRoot: false,
  canScanNexusIq: false, buildStep: 0, dependsOn: []
};

describe('PropertyOverrideModalComponent', () => {
  let fixture: ComponentFixture<PropertyOverrideModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [PropertyOverrideModalComponent] }).compileComponents();
    fixture = TestBed.createComponent(PropertyOverrideModalComponent);
    fixture.componentRef.setInput('project', project);
  });

  it('initializes from current property value and comment', () => {
    fixture.componentRef.setInput('prop', {
      name: 'library.version', value: '1.0', inheritedValue: null, source: 'Local POM', isOverridden: true,
      comment: 'existing reason'
    } satisfies ManagedProperty);
    fixture.detectChanges();

    expect(fixture.componentInstance.overrideNewValue()).toBe('1.0');
    expect(fixture.componentInstance.overrideRemark()).toBe('existing reason');
  });

  it('prefers Nexus IQ remediation and supplies security remark', () => {
    fixture.componentRef.setInput('prop', {
      name: 'library.version', value: '1.0', inheritedValue: '0.9', source: 'Parent', isOverridden: false,
      nexusIqViolations: [{
        componentIdentifier: 'maven:org.example:library:1.0', threatLevel: 9, policyName: 'Security',
        constraintViolations: ['test vulnerability'], remediationVersion: '1.2'
      }]
    } satisfies ManagedProperty);
    fixture.detectChanges();

    expect(fixture.componentInstance.overrideNewValue()).toBe('1.2');
    expect(fixture.componentInstance.overrideRemark()).toBe('Nexus IQ Security Fix');
  });

  it('emits edited value and remark', () => {
    fixture.componentRef.setInput('prop', {
      name: 'library.version', value: '1.0', inheritedValue: null, source: 'Local POM', isOverridden: true
    } satisfies ManagedProperty);
    fixture.detectChanges();
    fixture.componentInstance.overrideNewValue.set('2.0');
    fixture.componentInstance.overrideRemark.set('upgrade');
    const emitted: Array<{ newValue: string; remark: string }> = [];
    fixture.componentInstance.execute.subscribe(value => emitted.push(value));

    fixture.componentInstance.onExecute();

    expect(emitted).toEqual([{ newValue: '2.0', remark: 'upgrade' }]);
  });
});
