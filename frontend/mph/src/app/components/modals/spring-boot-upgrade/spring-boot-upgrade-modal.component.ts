import { Component, EventEmitter, Output, input, inject, OnInit, signal, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProjectAnalysis, MavenProjectService, SpringBootUpgradeSuggestions } from '../../../services/maven-project-service';

@Component({
  selector: 'app-spring-boot-upgrade-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spring-boot-upgrade-modal.component.html'
})
export class SpringBootUpgradeModalComponent implements OnInit {
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  project = input.required<ProjectAnalysis>();
  
  readonly suggestions = signal<SpringBootUpgradeSuggestions | null>(null);
  readonly isLoading = signal(true);
  readonly errorMessage = signal<string | null>(null);

  @Output() close = new EventEmitter<void>();
  @Output() upgrade = new EventEmitter<string>();

  ngOnInit(): void {
    const subscription = this.mavenProjectService.getSpringBootSuggestions(this.project().springBootVersion || '').subscribe({
      next: (s) => {
        this.suggestions.set(s);
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
