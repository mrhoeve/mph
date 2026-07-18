import { CommonModule } from '@angular/common';
import { Component, EventEmitter, inject, OnDestroy, Output, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ProjectStateService } from '../../../services/project-state-service';
import {
  RebaseProgress,
  RebaseProgressStatus,
  RebaseRepositoryPlan,
  RebaseWorkflowService
} from '../../../services/rebase-workflow.service';

interface RepositoryView extends RebaseRepositoryPlan {
  status: RebaseProgressStatus;
  message: string;
  recoveryHint: string | null;
  stashPreserved: boolean;
}

@Component({
  selector: 'app-rebase-develop-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './rebase-develop-modal.component.html',
  styleUrl: './rebase-develop-modal.component.css'
})
export class RebaseDevelopModalComponent implements OnDestroy {
  protected readonly projectState = inject(ProjectStateService);
  private readonly workflow = inject(RebaseWorkflowService);

  readonly status = RebaseProgressStatus;
  readonly started = signal(false);
  readonly running = signal(false);
  readonly detectedPrefix = signal<string | null>(null);
  readonly repositories = signal<RepositoryView[]>([]);
  readonly overall = signal<RebaseProgress | null>(null);
  readonly errorMessage = signal<string | null>(null);
  private eventsSubscription?: Subscription;
  private startSubscription?: Subscription;

  @Output() dismissed = new EventEmitter<void>();
  @Output() finished = new EventEmitter<RebaseProgressStatus>();

  start(): void {
    const paths = Array.from(this.projectState.selectedRootProjects());
    if (paths.length === 0 || this.running()) return;
    this.started.set(true);
    this.running.set(true);
    this.errorMessage.set(null);
    this.overall.set(null);
    this.repositories.set([]);

    this.startSubscription = this.workflow.start(paths).subscribe({
      next: response => {
        this.detectedPrefix.set(response.prefix);
        this.addMissingRepositories(response.repositories);
        this.eventsSubscription?.unsubscribe();
        this.eventsSubscription = this.workflow.events().subscribe({
          next: event => this.handleEvent(event),
          error: () => this.errorMessage.set(
            'The live progress connection was interrupted. The backend operation may still be running.'
          )
        });
      },
      error: error => {
        this.running.set(false);
        this.started.set(false);
        this.eventsSubscription?.unsubscribe();
        this.errorMessage.set(error.error?.message || error.message || 'The rebase preflight failed.');
      }
    });
  }

  dismiss(): void {
    if (!this.running()) this.dismissed.emit();
  }

  handleEvent(event: RebaseProgress): void {
    if (event.overall) {
      this.overall.set(event);
      if ([RebaseProgressStatus.COMPLETED, RebaseProgressStatus.PARTIAL, RebaseProgressStatus.FAILED].includes(event.status)) {
        this.running.set(false);
        this.finished.emit(event.status);
      }
      return;
    }
    if (!event.repositoryPath) return;
    this.repositories.update(items => {
      const index = items.findIndex(item => item.repositoryPath === event.repositoryPath);
      const updated: RepositoryView = {
        projectPath: event.projectPath || items[index]?.projectPath || '',
        artifactId: event.artifactId || items[index]?.artifactId || event.repositoryPath!,
        repositoryPath: event.repositoryPath!,
        status: event.status,
        message: event.message,
        recoveryHint: event.recoveryHint,
        stashPreserved: event.stashPreserved
      };
      if (index < 0) return [...items, updated];
      return items.map((item, itemIndex) => itemIndex === index ? updated : item);
    });
  }

  private addMissingRepositories(plans: RebaseRepositoryPlan[]): void {
    this.repositories.update(items => {
      const known = new Set(items.map(item => item.repositoryPath));
      return [
        ...items,
        ...plans.filter(plan => !known.has(plan.repositoryPath)).map(plan => ({
          ...plan,
          status: RebaseProgressStatus.PENDING,
          message: 'Waiting to be rebased.',
          recoveryHint: null,
          stashPreserved: false
        }))
      ];
    });
  }

  ngOnDestroy(): void {
    this.eventsSubscription?.unsubscribe();
    this.startSubscription?.unsubscribe();
  }
}
