import { Component, inject, Input, OnInit, OnChanges, SimpleChanges, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MavenProjectService, SbomDetails, SbomComponent, ProjectAnalysis } from '../../services/maven-project-service';

@Component({
  selector: 'app-sbom-item',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="sbom-item">
      <div class="item-content" [class.transitive]="level > 0">
        <span class="expand-icon" *ngIf="component.dependencies.length > 0" (click)="expanded.set(!expanded())">
          {{ expanded() ? '▼' : '▶' }}
        </span>
        <span class="spacer" *ngIf="component.dependencies.length === 0"></span>
        <span class="group-id">{{ component.groupId }}</span> : 
        <span class="artifact-id"><strong>{{ component.artifactId }}</strong></span>
        <span class="version-tag">{{ component.version }}</span>
        <span class="scope-tag" *ngIf="component.scope">{{ component.scope }}</span>
      </div>
      @if (expanded() && component.dependencies.length > 0) {
        <div class="children">
          @for (dep of component.dependencies; track dep.groupId + ':' + dep.artifactId + ':' + dep.version) {
            <app-sbom-item [component]="dep" [level]="level + 1"></app-sbom-item>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .sbom-item {
      margin-left: 0.75rem;
    }
    .children {
      border-left: 1px dashed #e2e8f0;
    }
    .item-content {
      padding: 0.25rem 0;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: 0.85rem;
      white-space: nowrap;
    }
    .item-content.transitive {
      color: #64748b;
    }
    .expand-icon {
      cursor: pointer;
      width: 0.8rem;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.7rem;
      color: #94a3b8;
    }
    .spacer {
      width: 0.8rem;
    }
    .artifact-id {
      color: #1e293b;
    }
    .version-tag {
      color: #3b82f6;
      font-size: 0.8rem;
      font-weight: 500;
    }
    .scope-tag {
      background: #f1f5f9;
      color: #64748b;
      padding: 0.05rem 0.3rem;
      border-radius: 0.25rem;
      font-size: 0.7rem;
      border: 1px solid #e2e8f0;
    }
  `]
})
export class SbomItemComponent {
  @Input({ required: true }) component!: SbomComponent;
  @Input({ required: true }) level!: number;
  expanded = signal(false);
}

@Component({
  selector: 'app-sbom-view',
  standalone: true,
  imports: [CommonModule, FormsModule, SbomItemComponent],
  template: `
    <div class="sbom-view">
      <div class="section-header">
        <h3>Software Bill of Materials (SBOM)</h3>
        <div class="export-actions">
          <button class="secondary-button" (click)="export('json')">Export JSON</button>
          <button class="secondary-button" (click)="export('xml')">Export XML</button>
        </div>
      </div>

      <div class="controls-row">
        @if (project.modules.length > 0) {
          <div class="module-selector">
            <select [ngModel]="selectedPath()" (ngModelChange)="onModuleChange($event)" class="module-select">
              <option [value]="project.path">{{ project.artifactId }} (Parent)</option>
              @for (module of project.modules; track module.path) {
                <option [value]="module.path">{{ module.artifactId }}</option>
              }
            </select>
          </div>
        }
        <div class="view-mode-toggle">
          <button class="toggle-btn" [class.active]="viewMode() === 'tree'" (click)="viewMode.set('tree')">Tree</button>
          <button class="toggle-btn" [class.active]="viewMode() === 'json'" (click)="viewMode.set('json')">JSON</button>
          <button class="toggle-btn" [class.active]="viewMode() === 'xml'" (click)="viewMode.set('xml')">XML</button>
        </div>
        <div class="search-bar" *ngIf="viewMode() === 'tree'">
          <input type="text" [ngModel]="searchTerm()" (ngModelChange)="searchTerm.set($event)" placeholder="Search components..." class="search-input">
        </div>
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
        <div class="sbom-content">
          @if (viewMode() === 'tree') {
            @if (searchTerm()) {
              <table class="sbom-table">
                <thead>
                  <tr>
                    <th>Group ID</th>
                    <th>Artifact ID</th>
                    <th>Version</th>
                    <th>Scope</th>
                  </tr>
                </thead>
                <tbody>
                  @for (comp of filteredComponents(); track comp.groupId + ':' + comp.artifactId + ':' + comp.version) {
                    <tr>
                      <td>{{ comp.groupId }}</td>
                      <td>{{ comp.artifactId }}</td>
                      <td><span class="version-tag">{{ comp.version }}</span></td>
                      <td><span class="scope-tag" *ngIf="comp.scope">{{ comp.scope }}</span></td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="4" class="empty-results">No components found matching your search.</td>
                    </tr>
                  }
                </tbody>
              </table>
            } @else {
              <div class="tree-container">
                @for (comp of components(); track comp.groupId + ':' + comp.artifactId + ':' + comp.version) {
                  <app-sbom-item [component]="comp" [level]="0"></app-sbom-item>
                } @empty {
                  <p class="empty-results">No dependencies found.</p>
                }
              </div>
            }
          } @else if (viewMode() === 'json') {
            <pre class="raw-content"><code>{{ rawJson() }}</code></pre>
          } @else if (viewMode() === 'xml') {
            <pre class="raw-content"><code>{{ rawXml() }}</code></pre>
          }
        </div>
        <p class="component-count" *ngIf="viewMode() === 'tree'">{{ totalComponents() }} components found</p>
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
    .controls-row {
      display: flex;
      gap: 1rem;
      margin-bottom: 1rem;
      align-items: center;
    }
    .module-selector {
      flex: 0 0 auto;
    }
    .module-select {
      padding: 0.6rem 1rem;
      border: 1px solid #e2e8f0;
      border-radius: 0.5rem;
      font-size: 0.9rem;
      background: white;
      color: #1e293b;
    }
    .search-bar {
      flex: 1;
    }
    .view-mode-toggle {
      display: flex;
      background: #f1f5f9;
      padding: 0.25rem;
      border-radius: 0.5rem;
      gap: 0.25rem;
    }
    .toggle-btn {
      padding: 0.4rem 0.8rem;
      border: none;
      background: transparent;
      border-radius: 0.4rem;
      font-size: 0.8rem;
      font-weight: 600;
      color: #64748b;
      cursor: pointer;
      transition: all 0.2s;
    }
    .toggle-btn.active {
      background: white;
      color: #3b82f6;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
    }
    .raw-content {
      margin: 0;
      font-family: 'JetBrains Mono', 'Fira Code', monospace;
      font-size: 0.8rem;
      line-height: 1.4;
      color: #334155;
      white-space: pre-wrap;
    }
    .scope-tag {
      background: #f1f5f9;
      color: #64748b;
      padding: 0.05rem 0.3rem;
      border-radius: 0.25rem;
      font-size: 0.7rem;
      border: 1px solid #e2e8f0;
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
    .sbom-content {
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 0.75rem;
      padding: 1rem;
      max-height: 600px;
      overflow: auto;
    }
    .sbom-table {
      width: 100%;
      border-collapse: collapse;
    }
    .sbom-table th {
      text-align: left;
      padding: 0.75rem 1rem;
      font-size: 0.8rem;
      color: #64748b;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      border-bottom: 1px solid #e2e8f0;
    }
    .sbom-table td {
      padding: 0.75rem 1rem;
      font-size: 0.9rem;
      color: #1e293b;
      border-bottom: 1px solid #f1f5f9;
    }
    .version-tag {
      background: #eff6ff;
      color: #2563eb;
      padding: 0.2rem 0.5rem;
      border-radius: 9999px;
      font-size: 0.75rem;
      font-weight: 600;
    }
    .empty-results {
      padding: 2rem;
      text-align: center;
      color: #64748b;
      font-style: italic;
    }
    .component-count {
      margin-top: 1rem;
      font-size: 0.85rem;
      color: #64748b;
      text-align: right;
    }
    .tree-container {
      padding-left: 0;
    }
    .secondary-button {
      background: white;
      color: #475569;
      border: 1px solid #e2e8f0;
      padding: 0.5rem 1rem;
      border-radius: 0.5rem;
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
    .loading-state, .error-state {
      padding: 3rem;
      text-align: center;
      background: #f8fafc;
      border-radius: 0.75rem;
      border: 1px dashed #cbd5e1;
    }
    .spinner.small {
      width: 2rem;
      height: 2rem;
      border-width: 3px;
      margin: 0 auto 1rem;
    }
  `]
})
export class SbomViewComponent implements OnInit, OnChanges {
  private readonly mavenProjectService = inject(MavenProjectService);

  @Input({ required: true }) project!: ProjectAnalysis;

  protected readonly components = signal<SbomComponent[]>([]);
  protected readonly rawXml = signal('');
  protected readonly rawJson = signal('');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly searchTerm = signal('');
  protected readonly selectedPath = signal('');
  protected readonly viewMode = signal<'tree' | 'json' | 'xml'>('tree');

  protected readonly flatComponents = computed(() => {
    const flat: SbomComponent[] = [];
    const traverse = (comps: SbomComponent[]) => {
      comps.forEach(c => {
        flat.push(c);
        if (c.dependencies) traverse(c.dependencies);
      });
    };
    traverse(this.components());
    // Remove duplicates based on GAV
    const unique = new Map<string, SbomComponent>();
    flat.forEach(c => unique.set(`${c.groupId}:${c.artifactId}:${c.version}`, c));
    return Array.from(unique.values());
  });

  protected readonly filteredComponents = computed(() => {
    const term = this.searchTerm().toLowerCase();
    if (!term) return this.flatComponents();
    return this.flatComponents().filter(c => 
      c.groupId.toLowerCase().includes(term) || 
      c.artifactId.toLowerCase().includes(term) ||
      c.version.toLowerCase().includes(term)
    );
  });

  protected readonly totalComponents = computed(() => this.flatComponents().length);

  ngOnInit() {
    this.selectedPath.set(this.project.path);
    this.loadDetails();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['project'] && !changes['project'].isFirstChange()) {
      this.selectedPath.set(this.project.path);
      this.searchTerm.set('');
      this.loadDetails();
    }
  }

  onModuleChange(path: string) {
    this.selectedPath.set(path);
    this.loadDetails();
  }

  protected loadDetails() {
    this.loading.set(true);
    this.error.set(null);
    this.mavenProjectService.getSbomDetails(this.selectedPath()).subscribe({
      next: (details) => {
        this.components.set(details.components);
        this.rawXml.set(details.rawXml);
        this.rawJson.set(details.rawJson);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load SBOM details. Please ensure the project is accessible.');
        this.loading.set(false);
        console.error('SBOM load error:', err);
      }
    });
  }

  export(format: string) {
    const url = this.mavenProjectService.getSbomExportUrl(this.selectedPath(), format);
    window.open(url, '_blank');
  }
}
