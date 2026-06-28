import { Component, DestroyRef, inject, OnInit, output, signal } from '@angular/core';
import { FileSystemService, FolderItem } from '../../services/file-system-service';

@Component({
  selector: 'app-folder-selector',
  templateUrl: './folder-selector.html',
  styleUrl: './folder-selector.css',
})
export class FolderSelector implements OnInit {
  readonly folderSelected = output<string>();

  protected readonly currentPath = signal('');
  protected readonly parentPath = signal<string | null>(null);
  protected readonly maxScanDepth = signal(3);
  protected readonly folders = signal<FolderItem[]>([]);
  protected readonly isLoading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  private readonly fileSystemService = inject(FileSystemService);
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

    if (!path) {
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set(null);

    const subscription = this.fileSystemService.saveBase(path, depth).subscribe({
      next: (folder) => {
        this.currentPath.set(folder.path);
        this.parentPath.set(folder.parentPath);
        this.maxScanDepth.set(folder.maxScanDepth);
        this.folders.set(folder.children);
        this.isLoading.set(false);
        this.folderSelected.emit(folder.path);
      },
      error: () => {
        this.errorMessage.set('Could not save the selected folder.');
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
    this.errorMessage.set(null);

    const subscription = requestFactory().subscribe({
      next: (folder) => {
        this.currentPath.set(folder.path);
        this.parentPath.set(folder.parentPath);
        this.maxScanDepth.set(folder.maxScanDepth);
        this.folders.set(folder.children);
        this.isLoading.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not load the selected folder.');
        this.isLoading.set(false);
      },
    });

    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }
}
