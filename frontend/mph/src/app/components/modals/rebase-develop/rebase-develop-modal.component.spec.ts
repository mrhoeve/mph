import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { vi } from 'vitest';
import { ProjectStateService } from '../../../services/project-state-service';
import { RebaseProgress, RebaseProgressStatus, RebaseWorkflowService } from '../../../services/rebase-workflow.service';
import { RebaseDevelopModalComponent } from './rebase-develop-modal.component';

describe('RebaseDevelopModalComponent', () => {
  const selected = signal(new Set(['/one/pom.xml', '/two/pom.xml']));
  const events = new Subject<RebaseProgress>();
  const workflow = { start: vi.fn(), events: vi.fn() };
  let fixture: ComponentFixture<RebaseDevelopModalComponent>;

  beforeEach(async () => {
    workflow.events.mockReset().mockReturnValue(events);
    workflow.start.mockReset().mockReturnValue(of({
      prefix: 'PREFIX-1234-',
      repositories: [
        { projectPath: '/one/pom.xml', artifactId: 'one', repositoryPath: '/one' },
        { projectPath: '/two/pom.xml', artifactId: 'two', repositoryPath: '/two' }
      ]
    }));
    selected.set(new Set(['/one/pom.xml', '/two/pom.xml']));
    await TestBed.configureTestingModule({
      imports: [RebaseDevelopModalComponent],
      providers: [
        { provide: ProjectStateService, useValue: { selectedRootProjects: selected } },
        { provide: RebaseWorkflowService, useValue: workflow }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(RebaseDevelopModalComponent);
    fixture.detectChanges();
  });

  it('explains and confirms the complete workflow before starting', () => {
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Stash tracked and untracked changes');
    expect(element.textContent).toContain('Source-code and structural POM conflicts');
    expect(element.textContent).toContain('2 selected projects');

    fixture.componentInstance.start();
    fixture.detectChanges();

    expect(workflow.start).toHaveBeenCalledWith(['/one/pom.xml', '/two/pom.xml']);
    expect(fixture.componentInstance.detectedPrefix()).toBe('PREFIX-1234-');
    expect(fixture.componentInstance.repositories()).toHaveLength(2);
  });

  it('tracks repository recovery details and completion', () => {
    const finished: RebaseProgressStatus[] = [];
    fixture.componentInstance.finished.subscribe(status => finished.push(status));
    fixture.componentInstance.start();

    events.next(progress({
      projectPath: '/one/pom.xml', artifactId: 'one', repositoryPath: '/one',
      status: RebaseProgressStatus.CONFLICT, message: 'conflict', recoveryHint: 'Resolve manually',
      stashPreserved: true
    }));
    events.next(progress({
      status: RebaseProgressStatus.PARTIAL, message: 'alignment skipped',
      recoveryHint: 'Run Version Update manually', overall: true, alignmentSkipped: true
    }));
    fixture.detectChanges();

    expect(fixture.componentInstance.repositories()[0].stashPreserved).toBe(true);
    expect(fixture.componentInstance.running()).toBe(false);
    expect(finished).toEqual([RebaseProgressStatus.PARTIAL]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Run Version Update manually');
  });

  it('shows preflight failures and returns to confirmation state', () => {
    workflow.start.mockReturnValueOnce(throwError(() => ({ error: { message: 'Prefixes differ' } })));

    fixture.componentInstance.start();
    fixture.detectChanges();

    expect(fixture.componentInstance.started()).toBe(false);
    expect(fixture.componentInstance.running()).toBe(false);
    expect(fixture.componentInstance.errorMessage()).toBe('Prefixes differ');
  });

  function progress(overrides: Partial<RebaseProgress>): RebaseProgress {
    return {
      projectPath: null, artifactId: null, repositoryPath: null,
      status: RebaseProgressStatus.RUNNING, message: 'running', recoveryHint: null,
      stashPreserved: false, overall: false, alignmentSkipped: false,
      ...overrides
    };
  }
});
