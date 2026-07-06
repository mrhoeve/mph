import { Component, inject, Input, OnInit, OnChanges, SimpleChanges, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MavenProjectService, SbomDetails, SbomComponent } from '../../services/maven-project-service';

@Component({
  selector: 'app-sbom-view',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="sbom-view">
      <div class="section-header">
        <h3>Software Bill of Materials (SBOM)</h3>
        <div class="export-actions">
          <button class="secondary-button" (click)="export('json')">Export JSON</button>
          <button class="secondary-button" (click)="export('xml')">Export XML</button>
        </div>
      </div>

      <div class="search-bar">
        <input type="text" [ngModel]="searchTerm()" (ngModelChange)="searchTerm.set($event)" placeholder="Search components..." class="search-input">
      </div>

      @if (loading()) {
        <div class="loading-state">
          <div class="spinner small"></div>
          <p>Loading dependencies...</p>
        </div>
      } @else if (error()) {
        <div class="error-state">
          <p>{{ error() }}</p>
          <button class="secondary-button" (click)="loadDetails()">Retry</button>
        </div>
      } @else {
        <table class="sbom-table">
          <thead>
            <tr>
              <th>Group ID</th>
              <th>Artifact ID</th>
              <th>Version</th>
              <th>Type</th>
            </tr>
          </thead>
          <tbody>
            @for (comp of filteredComponents(); track comp.groupId + ':' + comp.artifactId + ':' + comp.version) {
              <tr>
                <td>{{ comp.groupId }}</td>
                <td>{{ comp.artifactId }}</td>
                <td><span class="version-tag">{{ comp.version }}</span></td>
                <td>{{ comp.type }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="empty-results">No components found matching your search.</td>
              </tr>
            }
          </tbody>
        </table>
        <p class="component-count">{{ filteredComponents().length }} components found</p>
      }
    </div>
  `,
  styles: [`
    .sbom-view {
      margin-top: 2rem;
    }
    .section-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 1.5rem;
      padding-bottom: 0.5rem;
      border-bottom: 2px solid #f1f5f9;
    }
    .section-header h3 {
      margin: 0;
      font-size: 1.25rem;
      color: #334155;
    }
    .export-actions {
      display: flex;
      gap: 0.5rem;
    }
    .search-bar {
      margin-bottom: 1rem;
    }
    .search-input {
      width: 100%;
      padding: 0.6rem 1rem;
      border: 1px solid #e2e8f0;
      border-radius: 0.5rem;
      font-size: 0.9rem;
      box-sizing: border-box;
    }
    .search-input:focus {
      outline: none;
      border-color: #3b82f6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
    }
    .sbom-table {
      width: 100%;
      border-collapse: collapse;
      background: #f8fafc;
      border-radius: 0.75rem;
      overflow: hidden;
      border: 1px solid #e2e8f0;
    }
    .sbom-table th {
      text-align: left;
      padding: 0.75rem 1rem;
      font-size: 0.8rem;
      text-transform: uppercase;
      color: #64748b;
      letter-spacing: 0.05em;
      background: white;
      border-bottom: 1px solid #e2e8f0;
    }
    .sbom-table td {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid #f1f5f9;
      color: #475569;
      font-size: 0.9rem;
    }
    .version-tag {
      background: #f1f5f9;
      color: #475569;
      padding: 0.1rem 0.5rem;
      border-radius: 0.3rem;
      font-size: 0.8rem;
      font-weight: 600;
    }
    .component-count {
      margin-top: 0.75rem;
      font-size: 0.85rem;
      color: #64748b;
      text-align: right;
    }
    .loading-state, .error-state {
      padding: 2rem;
      text-align: center;
      color: #64748b;
      background: #f8fafc;
      border-radius: 0.75rem;
      border: 1px dashed #e2e8f0;
    }
    .spinner.small {
      width: 1.5rem;
      height: 1.5rem;
      border-width: 2px;
      margin: 0 auto 1rem;
    }
    .empty-results {
      text-align: center;
      padding: 2rem;
      color: #94a3b8;
      font-style: italic;
    }
    .secondary-button {
      background: white;
      color: #475569;
      border: 1px solid #e2e8f0;
      padding: 0.4rem 0.8rem;
      border-radius: 0.4rem;
      font-size: 0.85rem;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s;
    }
    .secondary-button:hover {
      background: #f8fafc;
      border-color: #cbd5e1;
      color: #1e293b;
    }
  `]
})
export class SbomViewComponent implements OnInit, OnChanges {
  private readonly mavenProjectService = inject(MavenProjectService);

  @Input({ required: true }) projectPath!: string;

  protected readonly details = signal<SbomDetails | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly searchTerm = signal('');

  protected readonly filteredComponents = computed(() => {
    const data = this.details();
    if (!data) return [];
    
    const term = this.searchTerm().toLowerCase();
    if (!term) return data.components;

    return data.components.filter(c => 
      c.groupId.toLowerCase().includes(term) || 
      c.artifactId.toLowerCase().includes(term) || 
      c.version.toLowerCase().includes(term)
    );
  });

  ngOnInit() {
    this.loadDetails();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['projectPath'] && !changes['projectPath'].isFirstChange()) {
      this.searchTerm.set('');
      this.loadDetails();
    }
  }

  protected loadDetails() {
    this.loading.set(true);
    this.error.set(null);
    this.mavenProjectService.getSbomDetails(this.projectPath).subscribe({
      next: (data) => {
        this.details.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load SBOM details');
        this.loading.set(false);
      }
    });
  }

  protected export(format: string) {
    const url = this.mavenProjectService.getSbomExportUrl(this.projectPath, format);
    window.open(url, '_blank');
  }
}
