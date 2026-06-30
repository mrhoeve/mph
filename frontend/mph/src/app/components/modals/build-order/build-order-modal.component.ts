import { Component, EventEmitter, Output, signal, inject, OnInit, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProjectAnalysis, MavenProjectService } from '../../../services/maven-project-service';
import mermaid from 'mermaid';
import svgPanZoom from 'svg-pan-zoom';

@Component({
  selector: 'app-build-order-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './build-order-modal.component.html'
})
export class BuildOrderModalComponent implements OnInit {
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  readonly buildOrderProjects = signal<ProjectAnalysis[]>([]);
  readonly isLoading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  
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
    const projects = this.buildOrderProjects();
    if (projects.length === 0) return;

    let mermaidContent = 'graph TD\n';
    
    projects.forEach(p => {
      const id = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
      mermaidContent += `  ${id}["${p.artifactId}"]\n`;
    });

    projects.forEach(p => {
      const pId = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
      const allModules = this.flatten(p);
      allModules.forEach(m => {
        m.usages.forEach(u => {
          const dependentRoot = this.findRootForUsage(projects, u.path);
          if (dependentRoot && dependentRoot.path !== p.path) {
            const depId = this.sanitizeId(`${dependentRoot.groupId}:${dependentRoot.artifactId}`);
            mermaidContent += `  ${pId} --> ${depId}\n`;
          }
        });
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
        this.addTooltips(projects);
      });
    }
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

  private addTooltips(projects: ProjectAnalysis[]): void {
    const svg = document.querySelector('#dependency-graph svg');
    if (!svg) return;

    projects.forEach(p => {
      const id = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
      const nodes = svg.querySelectorAll('.node');
      nodes.forEach(node => {
        if (node.id.includes(id)) {
          const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
          title.textContent = `Group: ${p.groupId}\nArtifact: ${p.artifactId}\nVersion: ${p.version}\nPath: ${p.path}`;
          node.appendChild(title);
        }
      });
    });
  }

  private sanitizeId(id: string): string {
    return id.replace(/[^a-zA-Z0-9]/g, '_');
  }

  private flatten(p: ProjectAnalysis): ProjectAnalysis[] {
    return [p, ...p.modules.flatMap(m => this.flatten(m))];
  }

  private findRootForUsage(roots: ProjectAnalysis[], path: string): ProjectAnalysis | null {
    return roots.find(r => this.flatten(r).some(m => m.path === path)) || null;
  }
}
