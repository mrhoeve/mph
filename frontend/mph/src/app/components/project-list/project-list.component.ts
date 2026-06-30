import { Component, inject, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProjectStateService } from '../../services/project-state-service';
import { ProjectAnalysis } from '../../services/maven-project-service';

@Component({
  selector: 'app-project-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './project-list.component.html',
  styleUrl: './project-list.component.css'
})
export class ProjectListComponent {
  protected readonly projectState = inject(ProjectStateService);

  @Output() bulkUpdate = new EventEmitter<void>();
  @Output() changeFolder = new EventEmitter<void>();

  selectProject(project: ProjectAnalysis): void {
    this.projectState.selectedProject.set(project);
  }

  isProjectSelected(project: ProjectAnalysis): boolean {
    return this.projectState.selectedRootProjects().has(project.path);
  }

  toggleProjectSelection(project: ProjectAnalysis): void {
    this.projectState.toggleProjectSelection(project.path);
  }
}
