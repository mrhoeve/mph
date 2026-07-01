import { Component, EventEmitter, Output, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectStateService } from '../../../services/project-state-service';

@Component({
  selector: 'app-bulk-update-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './bulk-update-modal.component.html'
})
export class BulkUpdateModalComponent {
  protected readonly projectState = inject(ProjectStateService);

  readonly bulkPrefix = signal('');
  readonly bulkUpdateDependents = signal(true);
  readonly bulkMode = signal('ADD_PREFIX');
  readonly gitBranchName = signal('');

  @Output() close = new EventEmitter<void>();
  @Output() execute = new EventEmitter<{paths: string[], prefix: string, updateDependents: boolean, mode: string, branchName: string}>();

  onExecute(): void {
    this.execute.emit({
      paths: Array.from(this.projectState.selectedRootProjects()),
      prefix: this.bulkPrefix(),
      updateDependents: this.bulkUpdateDependents(),
      mode: this.bulkMode(),
      branchName: this.gitBranchName()
    });
  }
}
