import { Component, EventEmitter, Output, input, inject, OnInit, signal, DestroyRef, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectAnalysis, MavenProjectService, ManagedProperty } from '../../../services/maven-project-service';

@Component({
  selector: 'app-managed-properties-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './managed-properties-modal.component.html'
})
export class ManagedPropertiesModalComponent implements OnInit {
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  project = input.required<ProjectAnalysis>();
  
  readonly properties = signal<ManagedProperty[]>([]);
  readonly isLoading = signal(true);
  readonly propertySearchQuery = signal('');
  readonly showOnlyOverrides = signal(false);

  @Output() close = new EventEmitter<void>();
  @Output() override = new EventEmitter<{prop: ManagedProperty}>();
  @Output() removeOverride = new EventEmitter<ManagedProperty>();

  readonly filteredProperties = computed(() => {
    const props = this.properties();
    const query = this.propertySearchQuery().toLowerCase();
    const onlyOverrides = this.showOnlyOverrides();

    return props.filter(p => {
      const matchesSearch = !query || 
        p.name.toLowerCase().includes(query) || 
        p.value.toLowerCase().includes(query);
      const matchesOverride = !onlyOverrides || p.isOverridden;
      return matchesSearch && matchesOverride;
    });
  });

  ngOnInit(): void {
    this.loadProperties();
  }

  loadProperties(): void {
    this.isLoading.set(true);
    const subscription = this.mavenProjectService.getManagedProperties(this.project().path).subscribe({
      next: (props) => {
        this.properties.set(props);
        this.isLoading.set(false);
      },
      error: () => {
        this.isLoading.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  onOverride(prop: ManagedProperty): void {
    this.override.emit({ prop });
  }

  onRemoveOverride(prop: ManagedProperty): void {
    this.removeOverride.emit(prop);
  }
}
