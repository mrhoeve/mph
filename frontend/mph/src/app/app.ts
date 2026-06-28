import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { FolderSelector } from './components/folder-selector/folder-selector';
import { FileSystemService } from './services/file-system-service';
import { MavenProjectService, ProjectAnalysis } from './services/maven-project-service';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, FolderSelector, CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly title = signal('MPH');
  protected readonly selectedBasePath = signal<string | null>(null);
  protected readonly isSelectingFolder = signal(true);
  protected readonly isLoadingBaseFolder = signal(true);
  protected readonly isScanning = signal(false);
  protected readonly projects = signal<ProjectAnalysis[]>([]);
  protected readonly selectedProject = signal<ProjectAnalysis | null>(null);
  protected readonly selectedRootProjects = signal<Set<string>>(new Set());
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly isBulkModalOpen = signal(false);
  protected readonly bulkPrefix = signal('');
  protected readonly bulkUpdateDependents = signal(true);
  protected readonly bulkMode = signal('ADD_PREFIX');

  private readonly fileSystemService = inject(FileSystemService);
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    const subscription = this.fileSystemService.current().subscribe({
      next: (folder) => {
        if (folder.remembered) {
          this.selectedBasePath.set(folder.path);
          this.isSelectingFolder.set(false);
          this.scan();
        } else {
          this.selectedBasePath.set(null);
          this.isSelectingFolder.set(true);
        }

        this.isLoadingBaseFolder.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not load the configured base folder.');
        this.isSelectingFolder.set(true);
        this.isLoadingBaseFolder.set(false);
      },
    });

    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected folderSelected(path: string): void {
    this.selectedBasePath.set(path);
    this.isSelectingFolder.set(false);
    this.scan();
  }

  protected changeFolder(): void {
    this.isSelectingFolder.set(true);
  }

  protected scan(): void {
    this.isScanning.set(true);
    this.errorMessage.set(null);
    const subscription = this.mavenProjectService.analyze().subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: (err) => {
        this.errorMessage.set('Scanning failed. Make sure the folder contains valid Maven projects.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected selectProject(project: ProjectAnalysis): void {
    this.selectedProject.set(project);
  }

  protected toggleProjectSelection(project: ProjectAnalysis): void {
    const selected = new Set(this.selectedRootProjects());
    if (selected.has(project.path)) {
      selected.delete(project.path);
    } else {
      selected.add(project.path);
    }
    this.selectedRootProjects.set(selected);
  }

  protected isProjectSelected(project: ProjectAnalysis): boolean {
    return this.selectedRootProjects().has(project.path);
  }

  protected toggleSelectAll(): void {
    if (this.isAllSelected()) {
      this.selectedRootProjects.set(new Set());
    } else {
      const allPaths = this.projects().map(p => p.path);
      this.selectedRootProjects.set(new Set(allPaths));
    }
  }

  protected isAllSelected(): boolean {
    return this.projects().length > 0 && this.selectedRootProjects().size === this.projects().length;
  }

  protected openBulkModal(): void {
    if (this.selectedRootProjects().size === 0) return;
    this.isBulkModalOpen.set(true);
  }

  protected closeBulkModal(): void {
    this.isBulkModalOpen.set(false);
  }

  protected executeBulkUpdate(): void {
    const paths = Array.from(this.selectedRootProjects());
    const prefix = this.bulkPrefix();
    const updateDependents = this.bulkUpdateDependents();
    const mode = this.bulkMode();

    this.closeBulkModal();
    this.isScanning.set(true);

    const subscription = this.mavenProjectService.bulkUpdateVersion(paths, prefix, updateDependents, mode).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
        this.selectedRootProjects.set(new Set());
        this.bulkPrefix.set('');
        this.bulkMode.set('ADD_PREFIX');
      },
      error: () => {
        this.errorMessage.set('Bulk update failed.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected updateToLatest(project: ProjectAnalysis): void {
    if (!project) return;
    
    this.isScanning.set(true);
    const subscription = this.mavenProjectService.updateVersion(project.groupId, project.artifactId, project.version).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to update versions.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  private updateProjectsData(projects: ProjectAnalysis[]): void {
    this.projects.set(projects);
    
    // Refresh selected project if one is currently selected
    const currentSelected = this.selectedProject();
    if (currentSelected) {
      const updated = this.findProjectByPath(projects, currentSelected.path);
      this.selectedProject.set(updated);
    }
  }

  private findProjectByPath(projects: ProjectAnalysis[], path: string): ProjectAnalysis | null {
    for (const project of projects) {
      if (project.path === path) return project;
      const found = this.findProjectByPath(project.modules, path);
      if (found) return found;
    }
    return null;
  }
}
