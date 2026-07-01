import { Component, EventEmitter, Output, signal, inject, OnInit, DestroyRef, computed, ElementRef, ViewChild, ViewChildren, QueryList, AfterViewInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectAnalysis, MavenProjectService } from '../../../services/maven-project-service';
import { MavenBuildService, BuildStatus, BuildOptions, ProjectProgress } from '../../../services/maven-build.service';
import { Subscription } from 'rxjs';

interface LogLine {
  id: number;
  text: string;
}

interface ProjectBuildInfo {
  project: ProjectAnalysis;
  status: BuildStatus;
  selected: boolean;
  originalIndex: number;
}

@Component({
  selector: 'app-maven-build-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './maven-build-modal.component.html'
})
export class MavenBuildModalComponent implements OnInit, AfterViewInit, OnDestroy {
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
  readonly maxParallel = signal(8);
  readonly showOptions = signal(true);
  readonly followLog = signal(true);

  readonly selectedProjectPath = signal<string | null>(null);
  readonly projectLogs = signal<Record<string, LogLine[]>>({});
  private logIdCounter = 0;

  private lastScrollTop = 0;
  private isAutoScrolling = false;

  readonly groupedProjects = computed(() => {
    const list = [...this.projects()];
    const currentlyBuilding = this.isBuilding();
    
    const hasResults = list.some(p => p.status === BuildStatus.FAILED || p.status === BuildStatus.SUCCESS || p.status === BuildStatus.RUNNING);

    if (!currentlyBuilding && !hasResults) {
      return [{
        name: '',
        projects: list.sort((a, b) => a.originalIndex - b.originalIndex)
      }];
    }

    const groups: { name: string, projects: ProjectBuildInfo[] }[] = [];
    
    const failed = list.filter(p => p.status === BuildStatus.FAILED).sort((a, b) => a.originalIndex - b.originalIndex);
    if (failed.length > 0) groups.push({ name: 'Failed', projects: failed });

    const running = list.filter(p => p.status === BuildStatus.RUNNING).sort((a, b) => a.originalIndex - b.originalIndex);
    if (running.length > 0) groups.push({ name: 'Running', projects: running });

    const pending = list.filter(p => p.status === BuildStatus.PENDING).sort((a, b) => a.originalIndex - b.originalIndex);
    if (pending.length > 0) groups.push({ name: 'Pending', projects: pending });

    const completed = list.filter(p => p.status === BuildStatus.SUCCESS || p.status === BuildStatus.SKIPPED).sort((a, b) => a.originalIndex - b.originalIndex);
    if (completed.length > 0) groups.push({ name: 'Completed', projects: completed });

    return groups;
  });
  
  private eventsSubscription?: Subscription;
  private logLinesSubscription?: Subscription;

  @ViewChild('logContainer') private logContainer?: ElementRef;
  @ViewChildren('logLine') private logLines?: QueryList<ElementRef>;
  @Output() close = new EventEmitter<void>();

  ngOnInit(): void {
    this.loadProjects();
  }

  ngAfterViewInit(): void {
    this.logLinesSubscription = this.logLines?.changes.subscribe(() => {
      if (this.followLog()) {
        this.scrollToBottom();
      }
    });
  }

  ngOnDestroy(): void {
    this.eventsSubscription?.unsubscribe();
    this.logLinesSubscription?.unsubscribe();
    this.stopBuild();
  }

  loadProjects(): void {
    this.isLoading.set(true);
    const subscription = this.mavenProjectService.getBuildOrder().subscribe({
      next: (roots) => {
        const all: ProjectBuildInfo[] = roots.map((r, index) => ({
          project: r,
          status: BuildStatus.PENDING,
          selected: true,
          originalIndex: index
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
    this.showOptions.set(false);
    this.projectLogs.set({});
    this.followLog.set(true);
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
      parallel: this.parallel(),
      maxParallel: this.maxParallel()
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
        const newLine: LogLine = { id: ++this.logIdCounter, text: event.logLine! };
        return { ...logs, [event.projectPath]: [...projectLogs, newLine].slice(-1000) };
      });
    }

    // Check if all selected projects are finished
    const allFinished = this.projects()
      .filter(p => p.selected)
      .every(p => p.status === BuildStatus.SUCCESS || p.status === BuildStatus.FAILED);
    
    if (allFinished && this.isBuilding()) {
      this.isBuilding.set(false);
      this.showOptions.set(true);
    }
  }

  stopBuild(): void {
    this.mavenBuildService.stopBuild().subscribe();
    this.isBuilding.set(false);
    this.showOptions.set(true);
  }

  selectProject(p: ProjectBuildInfo): void {
    this.selectedProjectPath.set(p.project.path);
    this.followLog.set(true);
    this.scrollToBottom();
  }

  getSelectedProjectLogs(): LogLine[] {
    const path = this.selectedProjectPath();
    return path ? (this.projectLogs()[path] || []) : [];
  }

  private scrollToBottom(): void {
    if (this.logContainer) {
      const element = this.logContainer.nativeElement;
      this.isAutoScrolling = true;
      element.scrollTop = element.scrollHeight;
      this.lastScrollTop = element.scrollTop;
      
      // Reset isAutoScrolling after a short delay to allow the scroll event to be processed
      setTimeout(() => {
        this.isAutoScrolling = false;
      }, 50);
    }
  }

  onLogScroll(): void {
    if (this.logContainer && !this.isAutoScrolling) {
      const element = this.logContainer.nativeElement;
      const currentScrollTop = element.scrollTop;
      
      // Use Math.ceil and a small tolerance to handle sub-pixel issues and zoom
      const atBottom = Math.ceil(element.scrollTop + element.clientHeight) >= element.scrollHeight - 10;
      
      // If user scrolls UP and is not at bottom, stop following
      if (currentScrollTop < this.lastScrollTop && !atBottom) {
        if (this.followLog()) {
          this.followLog.set(false);
        }
      } 
      // If they reach the bottom manually, resume following
      else if (atBottom) {
        if (!this.followLog()) {
          this.followLog.set(true);
        }
      }
      
      this.lastScrollTop = currentScrollTop;
    }
  }

  setFollowLog(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.followLog.set(checked);
    if (checked) {
      this.scrollToBottom();
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

  setMaxParallel(event: Event): void {
    const val = parseInt((event.target as HTMLInputElement).value, 10);
    if (!isNaN(val) && val > 0) {
      this.maxParallel.set(val);
    }
  }

  toggleOptions(): void {
    this.showOptions.update(v => !v);
  }
}
