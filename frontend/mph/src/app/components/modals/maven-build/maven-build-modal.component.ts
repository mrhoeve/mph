import { Component, EventEmitter, Output, signal, inject, OnInit, DestroyRef, computed, ElementRef, ViewChild, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectAnalysis, MavenProjectService } from '../../../services/maven-project-service';
import { MavenBuildService, BuildStatus, BuildOptions, ProjectProgress } from '../../../services/maven-build.service';
import { Subscription } from 'rxjs';

interface ProjectBuildInfo {
  project: ProjectAnalysis;
  status: BuildStatus;
  selected: boolean;
}

@Component({
  selector: 'app-maven-build-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './maven-build-modal.component.html'
})
export class MavenBuildModalComponent implements OnInit, AfterViewChecked {
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly mavenBuildService = inject(MavenBuildService);
  private readonly destroyRef = inject(DestroyRef);

  readonly projects = signal<ProjectBuildInfo[]>([]);
  readonly isLoading = signal(true);
  readonly isBuilding = signal(false);
  readonly errorMessage = signal<string | null>(null);
  
  readonly skipUTs = signal(true);
  readonly skipITs = signal(true);
  readonly parallel = signal(true);

  readonly selectedProjectPath = signal<string | null>(null);
  readonly projectLogs = signal<Record<string, string[]>>({});
  
  private eventsSubscription?: Subscription;
  private autoScroll = true;

  @ViewChild('logContainer') private logContainer?: ElementRef;
  @Output() close = new EventEmitter<void>();

  ngOnInit(): void {
    this.loadProjects();
  }

  ngAfterViewChecked(): void {
    if (this.autoScroll) {
      this.scrollToBottom();
    }
  }

  loadProjects(): void {
    this.isLoading.set(true);
    const subscription = this.mavenProjectService.getBuildOrder().subscribe({
      next: (roots) => {
        const all: ProjectBuildInfo[] = roots.map(r => ({
          project: r,
          status: BuildStatus.PENDING,
          selected: true
        }));
        this.projects.set(all);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set('Failed to load projects.');
        console.error(err);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  toggleSelectAll(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.projects.update(list => list.map(p => ({ ...p, selected: checked })));
  }

  isAllSelected(): boolean {
    return this.projects().every(p => p.selected);
  }

  startBuild(): void {
    const selectedPaths = this.projects().filter(p => p.selected).map(p => p.project.path);
    if (selectedPaths.length === 0) return;

    this.isBuilding.set(true);
    this.projectLogs.set({});
    this.projects.update(list => list.map(p => ({ ...p, status: p.selected ? BuildStatus.PENDING : BuildStatus.SKIPPED })));

    if (this.eventsSubscription) {
      this.eventsSubscription.unsubscribe();
    }

    this.eventsSubscription = this.mavenBuildService.getBuildEvents().subscribe({
      next: (event: ProjectProgress) => {
        this.handleBuildEvent(event);
      },
      error: (err) => {
        console.error('SSE Error:', err);
      }
    });

    const options: BuildOptions = {
      skipUTs: this.skipUTs(),
      skipITs: this.skipITs(),
      parallel: this.parallel()
    };

    this.mavenBuildService.startBuild(selectedPaths, options).subscribe({
      next: () => {
        // If no project is selected for logs, select the first one that is selected for build
        if (!this.selectedProjectPath()) {
          const firstSelected = this.projects().find(p => p.selected);
          if (firstSelected) {
            this.selectedProjectPath.set(firstSelected.project.path);
          }
        }
      },
      error: (err) => {
        this.errorMessage.set('Failed to start build.');
        this.isBuilding.set(false);
      }
    });
  }

  handleBuildEvent(event: ProjectProgress): void {
    this.projects.update(list => list.map(p => {
      if (p.project.path === event.projectPath) {
        return { ...p, status: event.status };
      }
      return p;
    }));

    if (event.logLine) {
      this.projectLogs.update(logs => {
        const projectLogs = logs[event.projectPath] || [];
        return { ...logs, [event.projectPath]: [...projectLogs, event.logLine!] };
      });
    }

    // Check if all selected projects are finished
    const allFinished = this.projects()
      .filter(p => p.selected)
      .every(p => p.status === BuildStatus.SUCCESS || p.status === BuildStatus.FAILED);
    
    if (allFinished && this.isBuilding()) {
      this.isBuilding.set(false);
    }
  }

  stopBuild(): void {
    this.mavenBuildService.stopBuild().subscribe();
    this.isBuilding.set(false);
  }

  selectProject(p: ProjectBuildInfo): void {
    this.selectedProjectPath.set(p.project.path);
    this.autoScroll = true;
  }

  getSelectedProjectLogs(): string[] {
    const path = this.selectedProjectPath();
    return path ? (this.projectLogs()[path] || []) : [];
  }

  private scrollToBottom(): void {
    if (this.logContainer) {
      try {
        this.logContainer.nativeElement.scrollTop = this.logContainer.nativeElement.scrollHeight;
      } catch (err) {}
    }
  }

  onLogScroll(): void {
    if (this.logContainer) {
      const element = this.logContainer.nativeElement;
      const atBottom = element.scrollHeight - element.scrollTop <= element.clientHeight + 10;
      this.autoScroll = atBottom;
    }
  }

  setSkipUTs(event: Event): void {
    this.skipUTs.set((event.target as HTMLInputElement).checked);
  }

  setSkipITs(event: Event): void {
    this.skipITs.set((event.target as HTMLInputElement).checked);
  }

  setParallel(event: Event): void {
    this.parallel.set((event.target as HTMLInputElement).checked);
  }
}
