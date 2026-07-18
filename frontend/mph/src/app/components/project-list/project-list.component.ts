import { Component, inject, Output, EventEmitter, signal } from '@angular/core';
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
  protected readonly expandedPaths = signal<Set<string>>(new Set());

  @Output() bulkUpdate = new EventEmitter<void>();
  @Output() changeFolder = new EventEmitter<void>();
  @Output() mavenBuild = new EventEmitter<void>();
  @Output() syncDevelop = new EventEmitter<void>();
  @Output() rebaseDevelop = new EventEmitter<void>();
  @Output() versionUpdate = new EventEmitter<void>();

  selectProject(project: ProjectAnalysis): void {
    this.projectState.selectedProject.set(project);
  }

  isProjectSelected(project: ProjectAnalysis): boolean {
    return this.projectState.selectedRootProjects().has(project.path);
  }

  toggleProjectSelection(project: ProjectAnalysis): void {
    this.projectState.toggleProjectSelection(project.path);
  }

  isExpanded(project: ProjectAnalysis): boolean {
    return this.expandedPaths().has(project.path);
  }

  toggleExpand(project: ProjectAnalysis, event: Event): void {
    event.stopPropagation();
    const current = new Set(this.expandedPaths());
    if (current.has(project.path)) {
      current.delete(project.path);
    } else {
      current.add(project.path);
    }
    this.expandedPaths.set(current);
  }
}
