import { Component, DestroyRef, inject, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FileSystemService, FolderItem } from '../../services/file-system-service';
import { ProjectStateService } from '../../services/project-state-service';

@Component({
  selector: 'app-folder-selector',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './folder-selector.html',
  styleUrl: './folder-selector.css',
})
export class FolderSelector implements OnInit {
  readonly folderSelected = output<string>();

  protected readonly currentPath = signal('');
  protected readonly parentPath = signal<string | null>(null);
  protected readonly maxScanDepth = signal(3);
  protected readonly nexusIqUrl = signal<string | undefined>(undefined);
  protected readonly nexusIqUser = signal<string | undefined>(undefined);
  protected readonly nexusIqPass = signal<string | undefined>(undefined);
  protected readonly nexusIqAppIdPrefix = signal<string | undefined>(undefined);
  protected readonly folders = signal<FolderItem[]>([]);
  protected readonly isLoading = signal(false);

  private readonly fileSystemService = inject(FileSystemService);
  private readonly projectState = inject(ProjectStateService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.loadCurrentFolder();
  }

  protected openFolder(path: string): void {
    this.loadFolder(() => this.fileSystemService.folders(path));
  }

  protected onDepthChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.maxScanDepth.set(parseInt(input.value, 10));
  }

  protected useCurrentFolder(): void {
    const path = this.currentPath();
    const depth = this.maxScanDepth();
    const iqUrl = this.nexusIqUrl();
    const iqUser = this.nexusIqUser();
    const iqPass = this.nexusIqPass();
    const iqAppIdPrefix = this.nexusIqAppIdPrefix();

    if (!path) {
      return;
    }

    this.isLoading.set(true);
    this.projectState.clearError();

    const subscription = this.fileSystemService.saveBase(path, depth, iqUrl, iqUser, iqPass, iqAppIdPrefix).subscribe({
      next: (folder) => {
        this.updateState(folder);
        this.folderSelected.emit(folder.path);
      },
      error: () => {
        this.projectState.setError('Could not save the selected folder.');
        this.isLoading.set(false);
      },
    });

    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  private loadCurrentFolder(): void {
    this.loadFolder(() => this.fileSystemService.current());
  }

  private loadFolder(requestFactory: () => ReturnType<FileSystemService['current']>): void {
    this.isLoading.set(true);
    this.projectState.clearError();

    const subscription = requestFactory().subscribe({
      next: (folder) => {
        this.updateState(folder);
      },
      error: () => {
        this.projectState.setError('Could not load the selected folder.');
        this.isLoading.set(false);
      },
    });

    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  private updateState(folder: any): void {
    this.currentPath.set(folder.path);
    this.parentPath.set(folder.parentPath);
    this.maxScanDepth.set(folder.maxScanDepth);
    this.nexusIqUrl.set(folder.nexusIqUrl);
    this.nexusIqUser.set(folder.nexusIqUser);
    this.nexusIqPass.set(folder.nexusIqPass);
    this.nexusIqAppIdPrefix.set(folder.nexusIqAppIdPrefix);
    this.folders.set(folder.children);
    this.isLoading.set(false);
  }
}
