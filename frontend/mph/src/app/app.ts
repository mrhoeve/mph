import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { FolderSelector } from './components/folder-selector/folder-selector';
import { FileSystemService } from './services/file-system-service';
import { MavenProjectService, ProjectAnalysis, ManagedProperty } from './services/maven-project-service';
import { SystemService, SystemInfo } from './services/system-service';
import { ProjectStateService } from './services/project-state-service';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import mermaid from 'mermaid';

import { ProjectListComponent } from './components/project-list/project-list.component';
import { ProjectDetailsComponent } from './components/project-details/project-details.component';
import { BulkUpdateModalComponent } from './components/modals/bulk-update/bulk-update-modal.component';
import { SpringBootUpgradeModalComponent } from './components/modals/spring-boot-upgrade/spring-boot-upgrade-modal.component';
import { ManagedPropertiesModalComponent } from './components/modals/managed-properties/managed-properties-modal.component';
import { BuildOrderModalComponent } from './components/modals/build-order/build-order-modal.component';
import { PropertyOverrideModalComponent } from './components/modals/property-override/property-override-modal.component';
import { MavenBuildModalComponent } from './components/modals/maven-build/maven-build-modal.component';
import { UpdateModulesModalComponent } from './components/modals/update-modules/update-modules-modal.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    RouterOutlet, 
    FolderSelector, 
    CommonModule, 
    FormsModule,
    ProjectListComponent,
    ProjectDetailsComponent,
    BulkUpdateModalComponent,
    SpringBootUpgradeModalComponent,
    ManagedPropertiesModalComponent,
    BuildOrderModalComponent,
    PropertyOverrideModalComponent,
    MavenBuildModalComponent,
    UpdateModulesModalComponent
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly title = signal('MPH');
  protected readonly selectedBasePath = signal<string | null>(null);
  protected readonly isSelectingFolder = signal(true);
  protected readonly isLoadingBaseFolder = signal(true);
  
  protected readonly isBulkModalOpen = signal(false);
  protected readonly isSpringBootModalOpen = signal(false);
  protected readonly isVersionsModalOpen = signal(false);
  protected readonly isOverrideModalOpen = signal(false);
  protected readonly systemInfo = signal<SystemInfo | null>(null);

  protected readonly versionsModalProject = signal<ProjectAnalysis | null>(null);
  protected readonly overridePropertyData = signal<{project: ProjectAnalysis, prop: ManagedProperty} | null>(null);

  private readonly fileSystemService = inject(FileSystemService);
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly systemService = inject(SystemService);
  protected readonly projectState = inject(ProjectStateService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    mermaid.initialize({ 
      startOnLoad: false, 
      theme: 'neutral',
      maxTextSize: 100000,
      flowchart: { useMaxWidth: false }
    });

    this.loadSystemInfo();

    const subscription = this.fileSystemService.current().subscribe({
      next: (folder) => {
        if (folder.remembered) {
          this.selectedBasePath.set(folder.path);
          this.isSelectingFolder.set(false);
          this.projectState.scan();
        } else {
          this.selectedBasePath.set(null);
          this.isSelectingFolder.set(true);
        }

        this.isLoadingBaseFolder.set(false);
      },
      error: () => {
        this.projectState.setError('Could not load the configured base folder.');
        this.isSelectingFolder.set(true);
        this.isLoadingBaseFolder.set(false);
      },
    });

    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  private loadSystemInfo(): void {
    const subscription = this.systemService.getInfo().subscribe({
      next: (info) => this.systemInfo.set(info),
      error: (err) => console.error('Failed to load system info', err)
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected folderSelected(path: string): void {
    this.selectedBasePath.set(path);
    this.isSelectingFolder.set(false);
    this.projectState.scan();
  }

  protected changeFolder(): void {
    this.isSelectingFolder.set(true);
  }

  protected executeBulkUpdate(data: {paths: string[], prefix: string, updateDependents: boolean, mode: string, branchName: string}): void {
    this.isBulkModalOpen.set(false);
    this.projectState.scanningMessage.set('Bulk updating versions...');
    this.projectState.isScanning.set(true);

    const subscription = this.mavenProjectService.bulkUpdateVersion(data.paths, data.prefix, data.updateDependents, data.mode, data.branchName, true).subscribe({
      next: (projects) => {
        this.projectState.updateProjectsData(projects);
        this.projectState.isScanning.set(false);
        this.projectState.selectedRootProjects.set(new Set());
      },
      error: () => {
        this.projectState.setError('Bulk update failed.');
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected executeSyncDevelop(): void {
    const selectedPaths = Array.from(this.projectState.selectedRootProjects());
    if (selectedPaths.length === 0) return;

    this.projectState.scanningMessage.set('Syncing develop branch...');
    this.projectState.isScanning.set(true);
    const subscription = this.mavenProjectService.syncDevelop(selectedPaths).subscribe({
      next: (response) => {
        this.projectState.updateProjectsData(response.projects);
        this.projectState.isScanning.set(false);
        this.projectState.selectedRootProjects.set(new Set());
        if (response.messages && response.messages.length > 0) {
          this.projectState.setInfo(response.messages.join('\n'));
        }
      },
      error: (err) => {
        this.projectState.setError(`Sync develop failed: ${err.error?.message || err.message}`);
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected executeVersionUpdate(): void {
    const selectedPaths = Array.from(this.projectState.selectedRootProjects());
    if (selectedPaths.length === 0) return;

    this.projectState.scanningMessage.set('Updating versions...');
    this.projectState.isScanning.set(true);
    const subscription = this.mavenProjectService.bulkUpdateVersion(
      selectedPaths,
      '',
      true,
      'CURRENT',
      null,
      false
    ).subscribe({
      next: (projects) => {
        this.projectState.updateProjectsData(projects);
        this.projectState.isScanning.set(false);
        this.projectState.setInfo(`Updated versions for ${selectedPaths.length} projects and their dependents.`);
      },
      error: (err) => {
        this.projectState.setError(`Version update failed: ${err.error?.message || err.message}`);
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected updateToLatest(project: ProjectAnalysis): void {
    this.projectState.projectForUpdateModules.set(project);
    this.projectState.isUpdateModulesModalOpen.set(true);
  }

  protected updateAllModulesAndUsages(project: ProjectAnalysis): void {
    this.projectState.projectForUpdateModules.set(project);
    this.projectState.isUpdateModulesModalOpen.set(true);
  }

  protected executeUpdateModulesAndUsages(data: {path: string, version: string}): void {
    this.projectState.isUpdateModulesModalOpen.set(false);
    this.projectState.scanningMessage.set('Updating modules and usages...');
    this.projectState.isScanning.set(true);
    const subscription = this.mavenProjectService.bulkUpdateVersion([data.path], data.version, true, 'MANUAL', null, false).subscribe({
      next: (projects) => {
        this.projectState.updateProjectsData(projects);
        this.projectState.isScanning.set(false);
      },
      error: (err) => {
        this.projectState.setError(`Update failed: ${err.error?.message || err.message}`);
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected upgradeSpringBoot(newVersion: string): void {
    const project = this.projectState.selectedProject();
    if (!project || !newVersion) return;

    this.isSpringBootModalOpen.set(false);
    this.projectState.scanningMessage.set('Upgrading Spring Boot...');
    this.projectState.isScanning.set(true);

    const subscription = this.mavenProjectService.upgradeSpringBoot(project.path, newVersion).subscribe({
      next: (projects) => {
        this.projectState.updateProjectsData(projects);
        this.projectState.isScanning.set(false);
      },
      error: () => {
        this.projectState.setError('Spring Boot upgrade failed.');
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected openVersionsModal(project: ProjectAnalysis): void {
    this.versionsModalProject.set(project);
    this.isVersionsModalOpen.set(true);
  }

  protected openOverrideModal(data: {prop: ManagedProperty}): void {
    const project = this.versionsModalProject();
    if (project) {
      this.overridePropertyData.set({ project, prop: data.prop });
      this.isOverrideModalOpen.set(true);
    }
  }

  protected executeOverride(data: {newValue: string, remark: string}): void {
    const overrideData = this.overridePropertyData();
    if (!overrideData) return;

    this.isOverrideModalOpen.set(false);
    this.projectState.scanningMessage.set('Overriding property...');
    this.projectState.isScanning.set(true);

    const subscription = this.mavenProjectService.overrideProperty(
      overrideData.project.path,
      overrideData.prop.name,
      data.newValue,
      data.remark
    ).subscribe({
      next: (projects) => {
        this.projectState.updateProjectsData(projects);
        this.projectState.isScanning.set(false);
        // Refresh properties in the modal if it's still open
        if (this.isVersionsModalOpen()) {
           // The ManagedPropertiesModalComponent handles its own refresh on init, 
           // but since it's already open, we might need a way to trigger refresh.
           // For now, let's just close it or assume the user will re-open if needed, 
           // but actually it's better if it updates.
           // Since properties is a signal in that component, and we don't have a direct handle,
           // we can toggle the modal project to trigger ngOnInit or similar.
           const p = this.versionsModalProject();
           this.versionsModalProject.set(null);
           setTimeout(() => this.versionsModalProject.set(p), 0);
        }
      },
      error: () => {
        this.projectState.setError('Property override failed.');
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected removeOverride(prop: ManagedProperty): void {
    const project = this.versionsModalProject();
    if (!project) return;

    if (!confirm(`Are you sure you want to remove the override for ${prop.name}?`)) {
      return;
    }

    this.projectState.scanningMessage.set('Removing property override...');
    this.projectState.isScanning.set(true);
    const subscription = this.mavenProjectService.removePropertyOverride(
      project.path,
      prop.name
    ).subscribe({
      next: (projects) => {
        this.projectState.updateProjectsData(projects);
        this.projectState.isScanning.set(false);
        // Refresh properties
        const p = this.versionsModalProject();
        this.versionsModalProject.set(null);
        setTimeout(() => this.versionsModalProject.set(p), 0);
      },
      error: () => {
        this.projectState.setError('Failed to remove property override.');
        this.projectState.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }
}
