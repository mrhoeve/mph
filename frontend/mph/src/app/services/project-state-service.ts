import { Injectable, signal, computed, inject, DestroyRef } from '@angular/core';
import { MavenProjectService, ProjectAnalysis, ManagedProperty } from './maven-project-service';

@Injectable({
  providedIn: 'root'
})
export class ProjectStateService {
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  readonly projects = signal<ProjectAnalysis[]>([]);
  readonly selectedProject = signal<ProjectAnalysis | null>(null);
  readonly selectedRootProjects = signal<Set<string>>(new Set());
  readonly isScanning = signal(false);
  readonly scanningMessage = signal<string>('Scanning projects...');
  isBuildOrderModalOpen = signal(false);
  isMavenBuildModalOpen = signal(false);
  isUpdateModulesModalOpen = signal(false);
  projectForUpdateModules = signal<ProjectAnalysis | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly infoMessage = signal<string | null>(null);
  private errorTimeout: any;
  private infoTimeout: any;

  clearError(): void {
    this.errorMessage.set(null);
    if (this.errorTimeout) {
      clearTimeout(this.errorTimeout);
      this.errorTimeout = null;
    }
  }

  clearInfo(): void {
    this.infoMessage.set(null);
    if (this.infoTimeout) {
      clearTimeout(this.infoTimeout);
      this.infoTimeout = null;
    }
  }

  setError(message: string): void {
    this.errorMessage.set(message);
    if (this.errorTimeout) {
      clearTimeout(this.errorTimeout);
    }
    this.errorTimeout = setTimeout(() => {
      this.errorMessage.set(null);
      this.errorTimeout = null;
    }, 10000); // 10 seconds
  }

  setInfo(message: string): void {
    this.infoMessage.set(message);
    if (this.infoTimeout) {
      clearTimeout(this.infoTimeout);
    }
    this.infoTimeout = setTimeout(() => {
      this.infoMessage.set(null);
      this.infoTimeout = null;
    }, 10000); // 10 seconds
  }

  readonly isAllSelected = computed(() => {
    const projs = this.projects();
    return projs.length > 0 && this.selectedRootProjects().size === projs.length;
  });

  readonly selectedProjectModuleUsages = computed(() => {
    const project = this.selectedProject();
    if (!project) return [];
    
    const result: ProjectAnalysis[] = [];
    const collect = (p: ProjectAnalysis) => {
      for (const m of p.modules) {
        if (m.usages.length > 0) {
          result.push(m);
        }
        collect(m);
      }
    };
    collect(project);
    return result;
  });

  scan(): void {
    this.scanningMessage.set('Scanning projects...');
    this.isScanning.set(true);
    this.clearError();
    const subscription = this.mavenProjectService.analyze().subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.setError('Scanning failed. Make sure the folder contains valid Maven projects.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  updateProjectsData(projects: ProjectAnalysis[]): void {
    this.projects.set(projects);
    
    const currentSelected = this.selectedProject();
    if (currentSelected) {
      const updated = this.findProjectByPath(projects, currentSelected.path);
      this.selectedProject.set(updated);
    }
  }

  findProjectByPath(projects: ProjectAnalysis[], path: string): ProjectAnalysis | null {
    for (const project of projects) {
      if (project.path === path) return project;
      const found = this.findProjectByPath(project.modules, path);
      if (found) return found;
    }
    return null;
  }

  toggleSelectAll(): void {
    if (this.isAllSelected()) {
      this.selectedRootProjects.set(new Set());
    } else {
      const allPaths = this.projects().map(p => p.path);
      this.selectedRootProjects.set(new Set(allPaths));
    }
  }

  toggleProjectSelection(path: string): void {
    const selected = new Set(this.selectedRootProjects());
    if (selected.has(path)) {
      selected.delete(path);
    } else {
      selected.add(path);
    }
    this.selectedRootProjects.set(selected);
  }
}
