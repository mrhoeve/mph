import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProjectStateService } from '../../../services/project-state-service';
import { BulkUpdateModalComponent } from './bulk-update-modal.component';

describe('BulkUpdateModalComponent', () => {
  let fixture: ComponentFixture<BulkUpdateModalComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BulkUpdateModalComponent],
      providers: [{ provide: ProjectStateService, useValue: { selectedRootProjects: signal(new Set(['/a', '/b'])) } }]
    }).compileComponents();
    fixture = TestBed.createComponent(BulkUpdateModalComponent);
    fixture.detectChanges();
  });

  it('renders selected count and disables empty update', () => {
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('p')?.textContent?.trim()).toBe('Modify the version of 2 selected projects.');
    expect((element.querySelector('.primary-button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('emits every configured bulk option', () => {
    fixture.componentInstance.bulkPrefix.set('DEV-');
    fixture.componentInstance.bulkUpdateDependents.set(false);
    fixture.componentInstance.bulkMode.set('REMOVE_PREFIX');
    fixture.componentInstance.gitBranchName.set('feature/update');
    const emitted: Array<{ paths: string[]; prefix: string; updateDependents: boolean; mode: string; branchName: string }> = [];
    fixture.componentInstance.execute.subscribe(value => emitted.push(value));

    fixture.componentInstance.onExecute();

    expect(emitted).toEqual([{
      paths: ['/a', '/b'], prefix: 'DEV-', updateDependents: false,
      mode: 'REMOVE_PREFIX', branchName: 'feature/update'
    }]);
  });

  it('updates the dependent-project option when its checkbox is clicked', () => {
    const checkbox = fixture.nativeElement.querySelector('#updateDependents') as HTMLInputElement;

    expect(fixture.componentInstance.bulkUpdateDependents()).toBe(true);
    checkbox.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.bulkUpdateDependents()).toBe(false);
  });
});
