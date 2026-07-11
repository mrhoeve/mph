import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NexusIqScanResponse } from '../../../services/maven-project-service';
import { NexusIqReportModalComponent } from './nexus-iq-report-modal.component';

describe('NexusIqReportModalComponent', () => {
  let fixture: ComponentFixture<NexusIqReportModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [NexusIqReportModalComponent] }).compileComponents();
    fixture = TestBed.createComponent(NexusIqReportModalComponent);
  });

  it('classifies every threat-level boundary', () => {
    const component = fixture.componentInstance;
    const finding = (threatLevel: number) => ({
      componentIdentifier: 'sample', policyName: 'policy', threatLevel, reasons: [], directDependency: true, waived: false, details: []
    });

    expect(component.severity(finding(8))).toBe('critical');
    expect(component.severity(finding(7))).toBe('severe');
    expect(component.severity(finding(4))).toBe('severe');
    expect(component.severity(finding(3))).toBe('moderate');
    expect(component.severity(finding(2))).toBe('moderate');
    expect(component.severity(finding(1))).toBe('low');
  });

  it('toggles expansion state', () => {
    const component = fixture.componentInstance;
    component.result = {
      message: 'test',
      violations: [
        { componentIdentifier: 'a', policyName: 'p', threatLevel: 5, reasons: [], directDependency: true, waived: false, details: [] },
        { componentIdentifier: 'b', policyName: 'p', threatLevel: 5, reasons: [], directDependency: true, waived: false, details: [] }
      ]
    } as any;

    expect(component.isExpanded(0)).toBeFalsy();
    component.toggleExpand(0);
    expect(component.isExpanded(0)).toBeTruthy();
    component.toggleExpand(0);
    expect(component.isExpanded(0)).toBeFalsy();

    component.toggleAll();
    expect(component.allExpanded).toBeTruthy();
    expect(component.isExpanded(0)).toBeTruthy();
    expect(component.isExpanded(1)).toBeTruthy();

    component.toggleAll();
    expect(component.allExpanded).toBeFalsy();
    expect(component.isExpanded(0)).toBeFalsy();
  });

  it('renders summary exact report link and detailed findings', () => {
    const result: NexusIqScanResponse = {
      message: 'Scan completed',
      reportUrl: 'https://iq.example.org/ui/report/1',
      summary: { critical: 1, severe: 2, moderate: 3, low: 4, total: 10, affectedComponents: 5 },
      violations: [{
        componentIdentifier: 'org.example : sample : 1.0', packageUrl: 'pkg:maven/org.example/sample@1.0',
        policyName: 'Security-Critical', threatLevel: 9, reasons: ['Found test vulnerability.'],
        directDependency: false, waived: true,
        details: [{
          policyName: 'Security-Critical', threatLevel: 9, reasons: ['Found test vulnerability.'], waived: true
        }]
      }]
    };
    fixture.componentRef.setInput('result', result);
    fixture.componentRef.setInput('projectName', 'sample-project');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect([...element.querySelectorAll('.summary-card strong')].map(node => node.textContent?.trim())).toEqual(['1', '2', '3', '4', '10', '5']);
    expect(element.querySelector('.nexus-report-link')?.getAttribute('href')).toBe(result.reportUrl);
    expect(element.querySelector('.finding h4')?.textContent?.trim()).toBe('org.example : sample : 1.0');

    // Details should be hidden by default
    expect(element.querySelector('.violation-details')).toBeNull();

    // Expand and check details
    fixture.componentInstance.toggleExpand(0);
    fixture.detectChanges();

    expect(element.querySelector('.detail-policy')?.textContent?.trim()).toBe('Security-Critical');
    expect(element.querySelector('.detail-reasons li')?.textContent?.trim()).toBe('Found test vulnerability.');
    expect([...element.querySelectorAll('.finding-badges span')].map(node => node.textContent?.trim())).toEqual(['Transitive', 'Waived']);
  });

  it('shows clean result when summary has no violations', () => {
    fixture.componentRef.setInput('result', {
      message: 'Scan completed',
      summary: { critical: 0, severe: 0, moderate: 0, low: 0, total: 0, affectedComponents: 0 },
      violations: []
    } satisfies NexusIqScanResponse);
    fixture.componentRef.setInput('projectName', 'clean-project');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.clean-result')?.textContent?.trim()).toBe(
      'No security policy violations were found in this report.'
    );
  });
});
