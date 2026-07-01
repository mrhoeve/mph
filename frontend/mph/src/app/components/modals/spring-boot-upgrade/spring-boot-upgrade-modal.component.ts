import { Component, EventEmitter, Output, input, inject, OnInit, signal, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProjectAnalysis, MavenProjectService } from '../../../services/maven-project-service';
import { SpringBootDiscoveryService, SpringBootUpgradeSuggestions } from '../../../services/spring-boot-discovery.service';

@Component({
  selector: 'app-spring-boot-upgrade-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spring-boot-upgrade-modal.component.html'
})
export class SpringBootUpgradeModalComponent implements OnInit {
  private readonly springBootDiscoveryService = inject(SpringBootDiscoveryService);
  private readonly destroyRef = inject(DestroyRef);

  project = input.required<ProjectAnalysis>();
  
  readonly suggestions = signal<SpringBootUpgradeSuggestions | null>(null);
  readonly isLoading = signal(true);
  readonly errorMessage = signal<string | null>(null);

  @Output() close = new EventEmitter<void>();
  @Output() upgrade = new EventEmitter<string>();

  ngOnInit(): void {
    this.loadFromBrowser();
  }

  private loadFromBrowser(): void {
    this.isLoading.set(true);
    const currentVersion = this.project().springBootVersion || '';
    const subscription = this.springBootDiscoveryService.getVersionsFromInitializr().subscribe({
      next: (versions) => {
        if (versions.length > 0) {
          const s = this.springBootDiscoveryService.getSuggestions(currentVersion, versions);
          this.suggestions.set(s);
        } else {
          this.errorMessage.set('Failed to retrieve Spring Boot versions.');
        }
        this.isLoading.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to load Spring Boot version suggestions.');
        this.isLoading.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  onUpgrade(version: string): void {
    this.upgrade.emit(version);
  }
}
