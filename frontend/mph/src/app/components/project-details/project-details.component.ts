import { Component, inject, Output, EventEmitter, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProjectStateService } from '../../services/project-state-service';
import { ProjectAnalysis } from '../../services/maven-project-service';
import { SbomViewComponent } from './sbom-view.component';

@Component({
  selector: 'app-project-details',
  standalone: true,
  imports: [CommonModule, SbomViewComponent],
  templateUrl: './project-details.component.html',
  styleUrl: './project-details.component.css'
})
export class ProjectDetailsComponent {
  protected readonly projectState = inject(ProjectStateService);

  @Output() updateAllModules = new EventEmitter<ProjectAnalysis>();
  @Output() manageProperties = new EventEmitter<ProjectAnalysis>();
  @Output() upgradeSpringBoot = new EventEmitter<ProjectAnalysis>();
  @Output() updateToLatest = new EventEmitter<ProjectAnalysis>();
  @Output() scanNexusIq = new EventEmitter<ProjectAnalysis>();

  protected readonly activeTab = signal<'analysis' | 'sbom'>('analysis');

}
