import { Component, EventEmitter, Output, signal, inject, OnInit, DestroyRef, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectAnalysis, MavenProjectService } from '../../../services/maven-project-service';
import mermaid from 'mermaid';
import svgPanZoom from 'svg-pan-zoom';

@Component({
  selector: 'app-build-order-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './build-order-modal.component.html'
})
export class BuildOrderModalComponent implements OnInit {
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  readonly buildOrderProjects = signal<ProjectAnalysis[]>([]);
  readonly isLoading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly selectedProjectPath = signal<string>('all');

  readonly allProjectsFlattened = computed(() => {
    return this.buildOrderProjects().flatMap(p => this.flatten(p)).sort((a, b) => a.artifactId.localeCompare(b.artifactId));
  });

  readonly hierarchicalProjects = computed(() => {
    const list: { project: ProjectAnalysis; depth: number; isParent: boolean }[] = [];
    const process = (p: ProjectAnalysis, depth: number) => {
      list.push({ project: p, depth, isParent: p.modules.length > 0 });
      const sortedModules = [...p.modules].sort((a, b) => a.artifactId.localeCompare(b.artifactId));
      sortedModules.forEach(m => process(m, depth + 1));
    };
    const sortedRoots = [...this.buildOrderProjects()].filter(p => p.isRoot)
      .sort((a, b) => a.artifactId.localeCompare(b.artifactId));
    sortedRoots.forEach(root => process(root, 0));
    return list;
  });
  
  private panZoomInstance: SvgPanZoom.Instance | null = null;

  @Output() close = new EventEmitter<void>();

  ngOnInit(): void {
    this.loadBuildOrder();
  }

  loadBuildOrder(): void {
    this.isLoading.set(true);
    const subscription = this.mavenProjectService.getBuildOrder().subscribe({
      next: (projects) => {
        this.buildOrderProjects.set(projects);
        this.isLoading.set(false);
        this.errorMessage.set(null);
        this.selectedProjectPath.set('all'); // Reset selection on reload
        setTimeout(() => this.renderDependencyGraph(), 100);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set('Failed to calculate build order. Please ensure the backend is running and there are no circular dependencies.');
        console.error('Build order error:', err);
      }
    });
    this.destroyRef.onDestroy(() => {
      subscription.unsubscribe();
      if (this.panZoomInstance) {
        this.panZoomInstance.destroy();
      }
    });
  }

  downloadExcel(): void {
    window.open(this.mavenProjectService.getExcelUrl(), '_blank');
  }

  zoomIn(): void {
    this.panZoomInstance?.zoomIn();
  }

  zoomOut(): void {
    this.panZoomInstance?.zoomOut();
  }

  resetZoom(): void {
    this.panZoomInstance?.reset();
    this.panZoomInstance?.center();
  }

  saveAsImage(): void {
    const svgElement = document.querySelector('#dependency-graph svg') as SVGSVGElement;
    if (!svgElement) return;

    const clonedSvg = svgElement.cloneNode(true) as SVGSVGElement;
    const bbox = svgElement.getBBox();
    const width = bbox.width + 40;
    const height = bbox.height + 40;
    
    clonedSvg.setAttribute('width', width.toString());
    clonedSvg.setAttribute('height', height.toString());
    clonedSvg.setAttribute('viewBox', `${bbox.x - 20} ${bbox.y - 20} ${width} ${height}`);

    const svgData = new XMLSerializer().serializeToString(clonedSvg);
    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    const img = new Image();

    const scale = 2;
    canvas.width = width * scale;
    canvas.height = height * scale;

    img.onload = () => {
      if (ctx) {
        ctx.fillStyle = 'white';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        
        try {
          const pngFile = canvas.toDataURL('image/png');
          const downloadLink = document.createElement('a');
          downloadLink.download = `dependency-graph-${new Date().getTime()}.png`;
          downloadLink.href = pngFile;
          downloadLink.click();
        } catch (e) {
          console.error('Failed to export image', e);
        }
      }
    };

    img.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svgData)));
  }

  private renderDependencyGraph(): void {
    const allProjects = this.buildOrderProjects();
    const allFlattened = this.allProjectsFlattened();
    if (allProjects.length === 0) return;

    const selectedPath = this.selectedProjectPath();
    let includedPaths = new Set<string>();

    if (selectedPath === 'all') {
      allFlattened.forEach(p => includedPaths.add(p.path));
    } else {
      const target = allFlattened.find(p => p.path === selectedPath);
      if (target) {
        // 1. Include the project and its descendants
        const targetAndDescendants = this.flatten(target);
        targetAndDescendants.forEach(p => includedPaths.add(p.path));

        // 2. Include direct dependencies and dependents of all currently included projects
        const directRelated = new Set<string>();
        allFlattened.forEach(p => {
          // Is p a dependency of any included project?
          const isDependency = p.usages.some(u => includedPaths.has(u.path));
          if (isDependency) {
            directRelated.add(p.path);
          }
          
          // Is p a dependent of any included project?
          if (includedPaths.has(p.path)) {
            p.usages.forEach(u => directRelated.add(u.path));
          }
        });
        
        directRelated.forEach(path => includedPaths.add(path));
        
        // 3. Ensure all included projects have their parent chain included to maintain hierarchy links
        const parentsToAdd = new Set<string>();
        includedPaths.forEach(path => {
          let current = allFlattened.find(p => p.path === path);
          while (current) {
            const parent = allFlattened.find(p => p.modules.some(m => m.path === current?.path));
            if (parent) {
              parentsToAdd.add(parent.path);
              current = parent;
            } else {
              current = undefined;
            }
          }
        });
        parentsToAdd.forEach(path => includedPaths.add(path));
      }
    }

    let mermaidContent = 'graph TD\n';
    mermaidContent += '  classDef parentNode font-weight:bold,fill:#f1f5f9,stroke:#334155,stroke-width:2px\n';
    mermaidContent += '  classDef moduleNode fill:#fff,stroke:#cbd5e1\n';
    
    // Render roots and their modules recursively (only if included)
    const roots = allProjects.filter(p => p.isRoot);
    roots.forEach(root => {
      mermaidContent += this.renderProjectNode(root, 1, includedPaths);
    });

    // Add dependency edges between included projects
    allFlattened.forEach(p => {
      if (!includedPaths.has(p.path)) return;
      
      const pId = this.getActiveNodeId(p, includedPaths);
      p.usages.forEach(u => {
        if (!includedPaths.has(u.path)) return;
        
        const dependentProject = allFlattened.find(ap => ap.path === u.path);
        if (dependentProject && dependentProject.path !== p.path) {
          const depId = this.getActiveNodeId(dependentProject, includedPaths);
          mermaidContent += `  ${pId} --> ${depId}\n`;
        }
      });
    });

    const element = document.getElementById('dependency-graph');
    if (element) {
      mermaid.render('graph-svg', mermaidContent).then(({ svg }) => {
        const processedSvg = svg
          .replace(/width="[\d.]+(px)?"/, '')
          .replace(/height="[\d.]+(px)?"/, '')
          .replace(/style="max-width: [\d.]+(px)?;"/, '');
        
        element.innerHTML = processedSvg;

        const svgElement = element.querySelector('svg');
        if (svgElement) {
          svgElement.setAttribute('width', '100%');
          svgElement.setAttribute('height', '100%');
          svgElement.style.maxWidth = 'none';
        }

        this.initializeZoom();
        this.addTooltips(allFlattened);
      });
    }
  }

  private renderProjectNode(p: ProjectAnalysis, indent: number, includedPaths: Set<string>): string {
    if (!includedPaths.has(p.path)) return '';

    const id = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
    const spaces = '  '.repeat(indent);
    let content = '';
    
    const includedModules = p.modules.filter(m => includedPaths.has(m.path));

    if (includedModules.length > 0) {
      // Use bold label for the parent node
      content += `${spaces}${id}_node["<b>${p.artifactId}</b>"]\n`;
      content += `${spaces}class ${id}_node parentNode\n`;
      
      includedModules.forEach(m => {
        content += this.renderProjectNode(m, indent + 1, includedPaths);
        // Link parent to module for visual clarity
        const mNodeId = this.getActiveNodeId(m, includedPaths);
        content += `${spaces}${id}_node -. module .-> ${mNodeId}\n`;
      });
    } else {
      content += `${spaces}${id}["${p.artifactId}"]\n`;
      content += `${spaces}class ${id} moduleNode\n`;
    }
    return content;
  }

  private getActiveNodeId(p: ProjectAnalysis, includedPaths: Set<string>): string {
    const id = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
    const includedModules = p.modules.filter(m => includedPaths.has(m.path));
    return includedModules.length > 0 ? `${id}_node` : id;
  }

  private initializeZoom(): void {
    const svgElement = document.querySelector('#dependency-graph svg') as SVGSVGElement;
    if (!svgElement) return;

    if (this.panZoomInstance) {
      this.panZoomInstance.destroy();
    }

    this.panZoomInstance = svgPanZoom(svgElement, {
      zoomEnabled: true,
      controlIconsEnabled: false,
      fit: true,
      center: true,
      minZoom: 0.1,
      maxZoom: 10
    });
  }

  private addTooltips(allProjects: ProjectAnalysis[]): void {
    const svg = document.querySelector('#dependency-graph svg');
    if (!svg) return;

    allProjects.forEach(p => {
      const id = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
      const nodes = svg.querySelectorAll('.node');
      nodes.forEach(node => {
        const nodeId = node.id;
        // Match both normal nodes and parent nodes
        if (nodeId.includes(id)) {
          const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
          title.textContent = `Group: ${p.groupId}\nArtifact: ${p.artifactId}\nVersion: ${p.version}\nPath: ${p.path}`;
          node.appendChild(title);
        }
      });
    });
  }

  getProjectDisplayName(item: { project: ProjectAnalysis; depth: number }): string {
    return '\u00A0'.repeat(item.depth * 4) + item.project.artifactId;
  }

  private sanitizeId(id: string): string {
    return id.replace(/[^a-zA-Z0-9]/g, '_');
  }

  onProjectFocusChange(event: any): void {
    this.selectedProjectPath.set(event.target.value);
    this.renderDependencyGraph();
  }

  private flatten(p: ProjectAnalysis): ProjectAnalysis[] {
    return [p, ...p.modules.flatMap(m => this.flatten(m))];
  }

  private findRootForUsage(roots: ProjectAnalysis[], path: string): ProjectAnalysis | null {
    return roots.find(r => this.flatten(r).some(m => m.path === path)) || null;
  }
}
