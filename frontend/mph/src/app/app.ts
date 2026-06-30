import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { FolderSelector } from './components/folder-selector/folder-selector';
import { FileSystemService } from './services/file-system-service';
import { MavenProjectService, ProjectAnalysis, ManagedProperty } from './services/maven-project-service';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import mermaid from 'mermaid';
import svgPanZoom from 'svg-pan-zoom';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, FolderSelector, CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly title = signal('MPH');
  protected readonly selectedBasePath = signal<string | null>(null);
  protected readonly isSelectingFolder = signal(true);
  protected readonly isLoadingBaseFolder = signal(true);
  protected readonly isScanning = signal(false);
  protected readonly projects = signal<ProjectAnalysis[]>([]);
  protected readonly selectedProject = signal<ProjectAnalysis | null>(null);
  protected readonly selectedRootProjects = signal<Set<string>>(new Set());
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly isBulkModalOpen = signal(false);
  protected readonly bulkPrefix = signal('');
  protected readonly bulkUpdateDependents = signal(true);
  protected readonly bulkMode = signal('ADD_PREFIX');

  protected readonly isSpringBootModalOpen = signal(false);
  protected readonly springBootSuggestions = signal<any | null>(null);
  protected readonly isLoadingSuggestions = signal(false);

  protected readonly isOverrideModalOpen = signal(false);
  protected readonly overridePropertyData = signal<{project: ProjectAnalysis, prop: ManagedProperty} | null>(null);
  protected readonly overrideNewValue = signal('');
  protected readonly overrideRemark = signal('');

  protected readonly isVersionsModalOpen = signal(false);
  protected readonly versionsModalProject = signal<ProjectAnalysis | null>(null);
  protected readonly versionsModalProperties = signal<ManagedProperty[]>([]);
  protected readonly isLoadingProperties = signal(false);
  protected readonly propertySearchQuery = signal('');
  protected readonly showOnlyOverrides = signal(false);

  protected readonly isBuildOrderModalOpen = signal(false);
  protected readonly buildOrderProjects = signal<ProjectAnalysis[]>([]);
  protected readonly isLoadingBuildOrder = signal(false);

  private panZoomInstance: SvgPanZoom.Instance | null = null;

  protected readonly filteredProperties = computed(() => {
    const props = this.versionsModalProperties();
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

  protected readonly selectedProjectModuleUsages = computed(() => {
    const project = this.selectedProject();
    if (!project) return [];
    
    const result: ProjectAnalysis[] = [];
    const collect = (p: ProjectAnalysis) => {
      for (const m of p.modules) {
        if (m.usages.length > 0) {
          result.push(m);
        }
        collect(m);
      }
    };
    collect(project);
    return result;
  });

  private readonly fileSystemService = inject(FileSystemService);
  private readonly mavenProjectService = inject(MavenProjectService);
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    mermaid.initialize({ 
      startOnLoad: false, 
      theme: 'neutral',
      flowchart: { useMaxWidth: false }
    });
    const subscription = this.fileSystemService.current().subscribe({
      next: (folder) => {
        if (folder.remembered) {
          this.selectedBasePath.set(folder.path);
          this.isSelectingFolder.set(false);
          this.scan();
        } else {
          this.selectedBasePath.set(null);
          this.isSelectingFolder.set(true);
        }

        this.isLoadingBaseFolder.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not load the configured base folder.');
        this.isSelectingFolder.set(true);
        this.isLoadingBaseFolder.set(false);
      },
    });

    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected folderSelected(path: string): void {
    this.selectedBasePath.set(path);
    this.isSelectingFolder.set(false);
    this.scan();
  }

  protected changeFolder(): void {
    this.isSelectingFolder.set(true);
  }

  protected scan(): void {
    this.isScanning.set(true);
    this.errorMessage.set(null);
    const subscription = this.mavenProjectService.analyze().subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: (err) => {
        this.errorMessage.set('Scanning failed. Make sure the folder contains valid Maven projects.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected selectProject(project: ProjectAnalysis): void {
    this.selectedProject.set(project);
  }

  protected toggleProjectSelection(project: ProjectAnalysis): void {
    const selected = new Set(this.selectedRootProjects());
    if (selected.has(project.path)) {
      selected.delete(project.path);
    } else {
      selected.add(project.path);
    }
    this.selectedRootProjects.set(selected);
  }

  protected isProjectSelected(project: ProjectAnalysis): boolean {
    return this.selectedRootProjects().has(project.path);
  }

  protected toggleSelectAll(): void {
    if (this.isAllSelected()) {
      this.selectedRootProjects.set(new Set());
    } else {
      const allPaths = this.projects().map(p => p.path);
      this.selectedRootProjects.set(new Set(allPaths));
    }
  }

  protected isAllSelected(): boolean {
    return this.projects().length > 0 && this.selectedRootProjects().size === this.projects().length;
  }

  protected openBulkModal(): void {
    if (this.selectedRootProjects().size === 0) return;
    this.isBulkModalOpen.set(true);
  }

  protected closeBulkModal(): void {
    this.isBulkModalOpen.set(false);
  }

  protected executeBulkUpdate(): void {
    const paths = Array.from(this.selectedRootProjects());
    const prefix = this.bulkPrefix();
    const updateDependents = this.bulkUpdateDependents();
    const mode = this.bulkMode();

    this.closeBulkModal();
    this.isScanning.set(true);

    const subscription = this.mavenProjectService.bulkUpdateVersion(paths, prefix, updateDependents, mode).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
        this.selectedRootProjects.set(new Set());
        this.bulkPrefix.set('');
        this.bulkMode.set('ADD_PREFIX');
      },
      error: () => {
        this.errorMessage.set('Bulk update failed.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected updateToLatest(project: ProjectAnalysis): void {
    if (!project) return;
    
    this.isScanning.set(true);
    const subscription = this.mavenProjectService.updateVersion(project.groupId, project.artifactId, project.version).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to update versions.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected updateAllModulesAndUsages(project: ProjectAnalysis): void {
    if (!project) return;

    this.isScanning.set(true);
    const subscription = this.mavenProjectService.bulkUpdateVersion([project.path], '', true, 'ADD_PREFIX').subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to update all modules.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected openSpringBootModal(project: ProjectAnalysis): void {
    if (!project) return;
    this.isLoadingSuggestions.set(true);
    this.isSpringBootModalOpen.set(true);
    this.springBootSuggestions.set(null);

    const subscription = this.mavenProjectService.getSpringBootSuggestions(project.springBootVersion || '').subscribe({
      next: (suggestions) => {
        this.springBootSuggestions.set(suggestions);
        this.isLoadingSuggestions.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to load Spring Boot version suggestions.');
        this.isLoadingSuggestions.set(false);
        this.isSpringBootModalOpen.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected closeSpringBootModal(): void {
    this.isSpringBootModalOpen.set(false);
  }

  protected upgradeSpringBoot(newVersion: String): void {
    const project = this.selectedProject();
    if (!project || !newVersion) return;

    this.closeSpringBootModal();
    this.isScanning.set(true);

    const subscription = this.mavenProjectService.upgradeSpringBoot(project.path, newVersion as string).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.errorMessage.set('Spring Boot upgrade failed.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected openVersionsModal(project: ProjectAnalysis): void {
    this.versionsModalProject.set(project);
    this.versionsModalProperties.set([]);
    this.propertySearchQuery.set('');
    this.showOnlyOverrides.set(false);
    this.isVersionsModalOpen.set(true);
    this.loadManagedProperties(project.path);
  }

  protected loadManagedProperties(path: string): void {
    this.isLoadingProperties.set(true);
    const subscription = this.mavenProjectService.getManagedProperties(path).subscribe({
      next: (props) => {
        this.versionsModalProperties.set(props);
        this.isLoadingProperties.set(false);
      },
      error: () => {
        this.isLoadingProperties.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected closeVersionsModal(): void {
    this.isVersionsModalOpen.set(false);
    this.versionsModalProject.set(null);
  }

  protected openOverrideModal(project: ProjectAnalysis, prop: ManagedProperty): void {
    this.overridePropertyData.set({ project, prop });
    this.overrideNewValue.set(prop.value);
    this.overrideRemark.set(prop.comment || '');
    this.isOverrideModalOpen.set(true);
  }

  protected closeOverrideModal(): void {
    this.isOverrideModalOpen.set(false);
  }

  protected executeOverride(): void {
    const data = this.overridePropertyData();
    if (!data) return;

    this.closeOverrideModal();
    this.isScanning.set(true);

    const subscription = this.mavenProjectService.overrideProperty(
      data.project.path,
      data.prop.name,
      this.overrideNewValue(),
      this.overrideRemark()
    ).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.errorMessage.set('Property override failed.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected removeOverride(project: ProjectAnalysis, prop: ManagedProperty): void {
    if (!confirm(`Are you sure you want to remove the override for ${prop.name}?`)) {
      return;
    }

    this.isScanning.set(true);
    const subscription = this.mavenProjectService.removePropertyOverride(
      project.path,
      prop.name
    ).subscribe({
      next: (projects) => {
        this.updateProjectsData(projects);
        this.isScanning.set(false);
      },
      error: () => {
        this.errorMessage.set('Failed to remove property override.');
        this.isScanning.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected openBuildOrderModal(): void {
    this.isBuildOrderModalOpen.set(true);
    this.isLoadingBuildOrder.set(true);
    this.buildOrderProjects.set([]);

    const subscription = this.mavenProjectService.getBuildOrder().subscribe({
      next: (projects) => {
        this.buildOrderProjects.set(projects);
        this.isLoadingBuildOrder.set(false);
        setTimeout(() => this.renderDependencyGraph(), 100);
      },
      error: () => {
        this.errorMessage.set('Failed to load build order.');
        this.isLoadingBuildOrder.set(false);
        this.isBuildOrderModalOpen.set(false);
      }
    });
    this.destroyRef.onDestroy(() => subscription.unsubscribe());
  }

  protected closeBuildOrderModal(): void {
    this.isBuildOrderModalOpen.set(false);
    if (this.panZoomInstance) {
      this.panZoomInstance.destroy();
      this.panZoomInstance = null;
    }
  }

  protected downloadExcel(): void {
    window.open(this.mavenProjectService.getExcelUrl(), '_blank');
  }

  protected zoomIn(): void {
    this.panZoomInstance?.zoomIn();
  }

  protected zoomOut(): void {
    this.panZoomInstance?.zoomOut();
  }

  protected resetZoom(): void {
    this.panZoomInstance?.reset();
    this.panZoomInstance?.center();
  }

  protected saveAsImage(): void {
    const svgElement = document.querySelector('#dependency-graph svg') as SVGSVGElement;
    if (!svgElement) return;

    // We need to clone it to avoid modifying the original one during export
    const clonedSvg = svgElement.cloneNode(true) as SVGSVGElement;
    
    // Ensure the SVG has explicit dimensions for the canvas
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

    // High res scale
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
          this.errorMessage.set('Failed to export image. Try a different browser.');
        }
      }
    };

    img.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svgData)));
  }

  private renderDependencyGraph(): void {
    const projects = this.buildOrderProjects();
    if (projects.length === 0) return;

    let mermaidContent = 'graph TD\n';
    
    // Add nodes
    projects.forEach(p => {
      const id = this.sanitizeId(`${p.groupId}:${p.artifactId}`);
      mermaidContent += `  ${id}["${p.artifactId}"]\n`;
    });

    // Add edges based on usages
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
        // Remove fixed width/height to allow the graph to fill its container
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

  private updateProjectsData(projects: ProjectAnalysis[]): void {
    this.projects.set(projects);
    
    // Refresh selected project if one is currently selected
    const currentSelected = this.selectedProject();
    if (currentSelected) {
      const updated = this.findProjectByPath(projects, currentSelected.path);
      this.selectedProject.set(updated);
    }

    // Refresh versions modal project if modal is open
    const currentVersionsProject = this.versionsModalProject();
    if (currentVersionsProject) {
      const updated = this.findProjectByPath(projects, currentVersionsProject.path);
      this.versionsModalProject.set(updated);
      this.loadManagedProperties(currentVersionsProject.path);
    }
  }

  private findProjectByPath(projects: ProjectAnalysis[], path: string): ProjectAnalysis | null {
    for (const project of projects) {
      if (project.path === path) return project;
      const found = this.findProjectByPath(project.modules, path);
      if (found) return found;
    }
    return null;
  }
}
